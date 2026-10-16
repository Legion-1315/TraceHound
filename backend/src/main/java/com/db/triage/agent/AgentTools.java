package com.db.triage.agent;

import com.db.triage.ledger.EvidenceLedger;
import com.db.triage.memory.IncidentMemoryService;
import com.db.triage.model.ScenarioDef;
import com.db.triage.model.Telemetry;
import com.db.triage.model.Topology;
import com.db.triage.redaction.RedactionService;
import com.db.triage.telemetry.LogSummarizer;
import com.db.triage.telemetry.TelemetrySource;
import com.db.triage.topology.TopologyService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The read-only tools the agent (scripted or LLM) can call. Every call is scrubbed for PII and
 * recorded in the evidence ledger; report citations point at those ledger entries. Telemetry comes
 * from the {@link TelemetrySource} — the simulation or the live estate.
 */
@Service
public class AgentTools {

    public record ToolResult(long ledgerId, String summary, Object detail) {
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

    private final TopologyService topologyService;
    private final TelemetrySource telemetry;
    private final EvidenceLedger ledger;
    private final RedactionService redaction;
    private final IncidentMemoryService memory;

    public AgentTools(TopologyService topologyService, TelemetrySource telemetry, EvidenceLedger ledger,
                      RedactionService redaction, IncidentMemoryService memory) {
        this.topologyService = topologyService;
        this.telemetry = telemetry;
        this.ledger = ledger;
        this.redaction = redaction;
        this.memory = memory;
    }

    public ToolResult getTopology(String incidentId, String flowId, String inference) {
        if (flowId != null && topologyService.flow(flowId).isEmpty()) {
            return record(incidentId, "get_topology", mapOf("flowId", flowId),
                    "Unknown flow '" + flowId + "'. Known flows: " + topologyService.topology().flows().stream()
                            .map(Topology.FlowDef::id).collect(Collectors.joining(", ")), null, inference);
        }
        List<Topology.ServiceDef> services = flowId == null
                ? topologyService.topology().services()
                : topologyService.subgraphForFlow(flowId);
        String summary = "Subgraph: " + services.stream().map(Topology.ServiceDef::id)
                .collect(Collectors.joining(" -> "));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("services", services);
        detail.put("flows", topologyService.topology().flows());
        return record(incidentId, "get_topology", mapOf("flowId", flowId), summary, detail, inference);
    }

    public ToolResult queryLogs(String incidentId, String serviceId, int minutes, String inference) {
        return queryLogs(incidentId, serviceId, minutes, null, inference);
    }

    public ToolResult queryLogs(String incidentId, String serviceId, int minutes, String minLevel, String inference) {
        Map<String, Object> params = mapOf("serviceId", serviceId, "minutes", minutes, "level", minLevel);
        Optional<String> unknown = unknownService(serviceId);
        if (unknown.isPresent()) {
            return record(incidentId, "query_logs", params, unknown.get(), null, inference);
        }
        List<Telemetry.LogLine> logs;
        try {
            logs = telemetry.queryLogs(serviceId, minutes);
        } catch (TelemetrySource.ServiceUnreachableException e) {
            return record(incidentId, "query_logs", params, "ERROR: " + e.getMessage(), null, inference);
        }
        List<LogSummarizer.Line> scrubbed = logs.stream()
                .filter(l -> minLevel == null || rank(l.level()) >= rank(minLevel))
                .map(l -> new LogSummarizer.Line(l.timestamp(), l.level(), l.correlationId(),
                        redaction.scrub(incidentId, l.message())))
                .toList();
        LogSummarizer.Summary s = LogSummarizer.summarize(scrubbed, 12, 10, 8);
        long warns = s.byLevel().getOrDefault("WARN", 0);
        long errors = s.byLevel().getOrDefault("ERROR", 0);
        String topLine = scrubbed.isEmpty() ? "no lines" : scrubbed.get(0).message();
        String summary = String.format("%d lines (%d ERROR, %d WARN) last %dm. Most recent: \"%s\"",
                scrubbed.size(), errors, warns, minutes, truncate(topLine, 110));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("window", "last " + minutes + "m, newest first, times UTC");
        detail.put("countsByLevel", s.byLevel());
        detail.put("patterns", s.patterns().stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("level", p.level());
            m.put("count", p.count());
            m.put("firstSeen", TS.format(p.firstSeen()));
            m.put("lastSeen", TS.format(p.lastSeen()));
            m.put("example", p.example());
            return m;
        }).toList());
        detail.put("recentWarnErrors", s.recentProblems().stream().map(this::line).toList());
        detail.put("mostRecent", s.recent().stream().map(this::line).toList());
        return record(incidentId, "query_logs", params, summary, detail, inference);
    }

    public ToolResult queryMetrics(String incidentId, String serviceId, String inference) {
        Map<String, Object> params = mapOf("serviceId", serviceId);
        Optional<String> unknown = unknownService(serviceId);
        if (unknown.isPresent()) {
            return record(incidentId, "query_metrics", params, unknown.get(), null, inference);
        }
        Telemetry.MetricsSummary m;
        try {
            m = telemetry.queryMetrics(serviceId);
        } catch (TelemetrySource.ServiceUnreachableException e) {
            return record(incidentId, "query_metrics", params, "ERROR: " + e.getMessage(), null, inference);
        }
        String summary = String.format("error-rate %.1f%%, throughput %.0f/min (baseline %.0f), p95 %dms",
                m.errorRate() * 100, m.throughputPerMin(), m.baselineThroughputPerMin(), m.latencyP95Ms());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("errorRatePct", round1(m.errorRate() * 100));
        detail.put("throughputPerMin", m.throughputPerMin());
        detail.put("baselineThroughputPerMin", m.baselineThroughputPerMin());
        detail.put("latencyP95Ms", m.latencyP95Ms());
        detail.put("last15MinutesThroughputPerMin", lastN(m.throughputSeries(), 15, 1));
        detail.put("last15MinutesErrorRatePct", lastN(m.errorRateSeries(), 15, 100));
        detail.put("seriesNote", "oldest first, one value per minute, last value = current minute");
        return record(incidentId, "query_metrics", params, summary, detail, inference);
    }

    public ToolResult getChangeEvents(String incidentId, List<String> serviceIds, int minutes, String inference) {
        Map<String, Object> params = mapOf("services", serviceIds, "minutes", minutes);
        List<Telemetry.ChangeEvent> events;
        try {
            events = telemetry.changeEvents(serviceIds == null ? List.of() : serviceIds, minutes);
        } catch (TelemetrySource.ServiceUnreachableException e) {
            return record(incidentId, "get_change_events", params, "ERROR: " + e.getMessage(), null, inference);
        } catch (IllegalArgumentException e) {
            return record(incidentId, "get_change_events", params, e.getMessage(), null, inference);
        }
        String summary = events.isEmpty()
                ? "No change events in window"
                : events.stream()
                .map(e -> String.format("%s: %s (%dm ago, %s)", e.serviceId(), truncate(e.summary(), 80),
                        minutesAgo(e), e.type()))
                .collect(Collectors.joining(" | "));
        List<Map<String, Object>> detail = events.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", TS.format(e.timestamp()));
            m.put("minutesAgo", minutesAgo(e));
            m.put("service", e.serviceId());
            m.put("type", e.type());
            m.put("summary", e.summary());
            m.put("author", e.author());
            return m;
        }).toList();
        return record(incidentId, "get_change_events", params, summary, detail, inference);
    }

    public ToolResult compareSchema(String incidentId, String serviceId, String inference) {
        Map<String, Object> params = mapOf("serviceId", serviceId);
        Optional<String> unknown = unknownService(serviceId);
        if (unknown.isPresent()) {
            return record(incidentId, "compare_schema", params, unknown.get(), null, inference);
        }
        Optional<ScenarioDef.SchemaDiff> diff;
        try {
            diff = telemetry.schemaDiff(serviceId);
        } catch (TelemetrySource.ServiceUnreachableException e) {
            return record(incidentId, "compare_schema", params, "ERROR: " + e.getMessage(), null, inference);
        }
        String summary = diff
                .map(d -> "Schema diff for " + d.release() + ": " + String.join("; ", d.changes()))
                .orElse("No schema changes detected since last release for " + serviceId);
        return record(incidentId, "compare_schema", params, summary, diff.orElse(null), inference);
    }

    public ToolResult searchIncidentMemory(String incidentId, String symptoms, String inference) {
        List<IncidentMemoryService.Match> matches = memory.search(symptoms == null ? "" : symptoms, 3);
        String summary = matches.isEmpty()
                ? "No similar past incidents found (memory has " + memory.size() + " entries)"
                : matches.stream()
                .map(m -> String.format("[sim %.0f%%] %s -> %s", m.similarity() * 100,
                        truncate(m.incident().symptoms(), 60), truncate(m.incident().rootCause(), 80)))
                .collect(Collectors.joining(" | "));
        return record(incidentId, "search_incident_memory", mapOf("symptoms", symptoms), summary,
                matches, inference);
    }

    private Optional<String> unknownService(String serviceId) {
        if (serviceId != null && topologyService.service(serviceId).isPresent()) {
            return Optional.empty();
        }
        return Optional.of("Unknown service '" + serviceId + "'. Known services: " + topologyService.topology()
                .services().stream().map(Topology.ServiceDef::id).collect(Collectors.joining(", ")));
    }

    private Map<String, String> line(LogSummarizer.Line l) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ts", TS.format(l.timestamp()));
        m.put("level", l.level());
        m.put("corr", l.correlationId());
        m.put("msg", l.message());
        return m;
    }

    private ToolResult record(String incidentId, String tool, Map<String, Object> params, String summary,
                              Object detail, String inference) {
        var entry = ledger.append(incidentId, tool, params, summary, inference == null ? "" : inference);
        return new ToolResult(entry.id(), summary, detail);
    }

    private static List<Double> lastN(List<Telemetry.MetricPoint> series, int n, double scale) {
        if (series == null || series.isEmpty()) return List.of();
        List<Double> out = new ArrayList<>();
        for (Telemetry.MetricPoint p : series.subList(Math.max(0, series.size() - n), series.size())) {
            out.add(round1(p.value() * scale));
        }
        return out;
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] != null) m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static int rank(String level) {
        return switch (level == null ? "" : level.toUpperCase()) {
            case "ERROR" -> 4;
            case "WARN" -> 3;
            case "INFO" -> 2;
            case "DEBUG" -> 1;
            default -> 0;
        };
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private long minutesAgo(Telemetry.ChangeEvent e) {
        return Duration.between(e.timestamp(), Instant.now()).toMinutes();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
