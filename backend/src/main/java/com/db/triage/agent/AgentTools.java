package com.db.triage.agent;

import com.db.triage.ledger.EvidenceLedger;
import com.db.triage.memory.IncidentMemoryService;
import com.db.triage.model.ScenarioDef;
import com.db.triage.model.Telemetry;
import com.db.triage.model.Topology;
import com.db.triage.redaction.RedactionService;
import com.db.triage.sim.SimulationEngine;
import com.db.triage.topology.TopologyService;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The read-only tools the agent (scripted or LLM) can call. Every call is scrubbed for PII
 * and recorded in the evidence ledger; report citations point at those ledger entries.
 */
@Service
public class AgentTools {

    public record ToolResult(long ledgerId, String summary, Object detail) {
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

    private final TopologyService topologyService;
    private final SimulationEngine sim;
    private final EvidenceLedger ledger;
    private final RedactionService redaction;
    private final IncidentMemoryService memory;

    public AgentTools(TopologyService topologyService, SimulationEngine sim, EvidenceLedger ledger,
                      RedactionService redaction, IncidentMemoryService memory) {
        this.topologyService = topologyService;
        this.sim = sim;
        this.ledger = ledger;
        this.redaction = redaction;
        this.memory = memory;
    }

    public ToolResult getTopology(String incidentId, String flowId, String inference) {
        List<Topology.ServiceDef> services = flowId == null
                ? topologyService.topology().services()
                : topologyService.subgraphForFlow(flowId);
        String summary = "Subgraph: " + services.stream().map(Topology.ServiceDef::id)
                .collect(Collectors.joining(" -> "));
        return record(incidentId, "get_topology", mapOf("flowId", flowId), summary, services, inference);
    }

    public ToolResult queryLogs(String incidentId, String serviceId, int minutes, String inference) {
        List<Telemetry.LogLine> logs = sim.queryLogs(serviceId, minutes);
        List<Map<String, String>> scrubbed = logs.stream()
                .map(l -> Map.of(
                        "ts", TS.format(l.timestamp()),
                        "level", l.level(),
                        "corr", l.correlationId(),
                        "msg", redaction.scrub(incidentId, l.message())))
                .toList();
        long warns = logs.stream().filter(l -> l.level().equals("WARN")).count();
        long errors = logs.stream().filter(l -> l.level().equals("ERROR")).count();
        String topLine = scrubbed.isEmpty() ? "no lines" : scrubbed.get(0).get("msg");
        String summary = String.format("%d lines (%d ERROR, %d WARN) last %dm. Most recent: \"%s\"",
                logs.size(), errors, warns, minutes, truncate(topLine, 110));
        return record(incidentId, "query_logs", mapOf("serviceId", serviceId, "minutes", minutes),
                summary, scrubbed, inference);
    }

    public ToolResult queryMetrics(String incidentId, String serviceId, String inference) {
        Telemetry.MetricsSummary m = sim.queryMetrics(serviceId);
        String summary = String.format("error-rate %.1f%%, throughput %.0f/min (baseline %.0f), p95 %dms",
                m.errorRate() * 100, m.throughputPerMin(), m.baselineThroughputPerMin(), m.latencyP95Ms());
        return record(incidentId, "query_metrics", mapOf("serviceId", serviceId), summary, m, inference);
    }

    public ToolResult getChangeEvents(String incidentId, List<String> serviceIds, int minutes, String inference) {
        List<Telemetry.ChangeEvent> events = sim.changeEvents(serviceIds == null ? List.of() : serviceIds, minutes);
        String summary = events.isEmpty()
                ? "No change events in window"
                : events.stream()
                .map(e -> String.format("%s: %s (%dm ago, %s)", e.serviceId(), truncate(e.summary(), 80),
                        minutesAgo(e), e.type()))
                .collect(Collectors.joining(" | "));
        return record(incidentId, "get_change_events",
                mapOf("services", serviceIds, "minutes", minutes), summary, events, inference);
    }

    public ToolResult compareSchema(String incidentId, String serviceId, String inference) {
        Optional<ScenarioDef.SchemaDiff> diff = sim.schemaDiff(serviceId);
        String summary = diff
                .map(d -> "Schema diff for " + d.release() + ": " + String.join("; ", d.changes()))
                .orElse("No schema changes detected since last release for " + serviceId);
        return record(incidentId, "compare_schema", mapOf("serviceId", serviceId), summary,
                diff.orElse(null), inference);
    }

    public ToolResult searchIncidentMemory(String incidentId, String symptoms, String inference) {
        List<IncidentMemoryService.Match> matches = memory.search(symptoms, 3);
        String summary = matches.isEmpty()
                ? "No similar past incidents found (memory has " + memory.size() + " entries)"
                : matches.stream()
                .map(m -> String.format("[sim %.0f%%] %s -> %s", m.similarity() * 100,
                        truncate(m.incident().symptoms(), 60), truncate(m.incident().rootCause(), 80)))
                .collect(Collectors.joining(" | "));
        return record(incidentId, "search_incident_memory", mapOf("symptoms", symptoms), summary,
                matches, inference);
    }

    private ToolResult record(String incidentId, String tool, Map<String, Object> params, String summary,
                              Object detail, String inference) {
        var entry = ledger.append(incidentId, tool, params, summary, inference == null ? "" : inference);
        return new ToolResult(entry.id(), summary, detail);
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] != null) m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private long minutesAgo(Telemetry.ChangeEvent e) {
        return java.time.Duration.between(e.timestamp(), java.time.Instant.now()).toMinutes();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
