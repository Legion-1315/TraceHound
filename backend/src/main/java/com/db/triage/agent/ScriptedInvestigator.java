package com.db.triage.agent;

import com.db.triage.incident.Incident;
import com.db.triage.ledger.EvidenceLedger;
import com.db.triage.memory.IncidentMemoryService;
import com.db.triage.model.ScenarioDef;
import com.db.triage.model.TriageReport;
import com.db.triage.redaction.RedactionService;
import com.db.triage.topology.TopologyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Deterministic investigator: replays the correct investigation for the active scenario
 * with realistic pacing, calling the REAL tools (so the evidence ledger is genuine) and
 * emitting the same WebSocket events as the LLM mode. This is the demo safety net.
 */
@Service
public class ScriptedInvestigator {

    private final AgentTools tools;
    private final InvestigationEventPublisher events;
    private final TopologyService topology;
    private final EvidenceLedger ledger;
    private final RedactionService redaction;
    private final IncidentMemoryService memory;
    private final long delayMinMs;
    private final long delayMaxMs;

    public ScriptedInvestigator(AgentTools tools, InvestigationEventPublisher events, TopologyService topology,
                                EvidenceLedger ledger, RedactionService redaction, IncidentMemoryService memory,
                                @Value("${triage.step-delay-min-ms}") long delayMinMs,
                                @Value("${triage.step-delay-max-ms}") long delayMaxMs) {
        this.tools = tools;
        this.events = events;
        this.topology = topology;
        this.ledger = ledger;
        this.redaction = redaction;
        this.memory = memory;
        this.delayMinMs = delayMinMs;
        this.delayMaxMs = delayMaxMs;
    }

    public TriageReport investigate(Incident incident, ScenarioDef scenario) {
        Instant start = Instant.now();
        Map<String, Long> ledgerKeys = new HashMap<>();
        boolean shortCircuited = false;

        List<ScenarioDef.ScriptStep> steps = scenario.script();
        for (int i = 0; i < steps.size(); i++) {
            ScenarioDef.ScriptStep step = steps.get(i);
            pace();
            AgentTools.ToolResult result = executeStep(incident, step, ledgerKeys);

            // Learning loop: a high-similarity past incident lets us jump to direct verification.
            if (result != null && "search_incident_memory".equals(step.tool())
                    && scenario.shortcutScript() != null && !scenario.shortcutScript().isEmpty()
                    && topSimilarity(result) >= 0.60) {
                events.thought(incident.getId(),
                        "High-similarity past incident found in memory — skipping broad sweep, validating the known cause directly.");
                for (ScenarioDef.ScriptStep shortcutStep : scenario.shortcutScript()) {
                    pace();
                    executeStep(incident, shortcutStep, ledgerKeys);
                }
                shortCircuited = true;
                break;
            }
        }

        TriageReport report = buildReport(incident, scenario, ledgerKeys, start, shortCircuited);
        pace();
        events.reportReady(incident.getId());
        rememberIncident(scenario, report);
        return report;
    }

    private AgentTools.ToolResult executeStep(Incident incident, ScenarioDef.ScriptStep step,
                                              Map<String, Long> ledgerKeys) {
        String id = incident.getId();
        if (step.thought() != null) {
            events.thought(id, step.thought());
        }
        if (step.nodeStatus() != null) {
            step.nodeStatus().forEach((service, status) -> events.nodeStatus(id, service, status));
        }
        AgentTools.ToolResult result = null;
        if (step.tool() != null) {
            result = dispatch(id, step);
            incident.incrementToolCalls();
            if (step.ledgerKey() != null) {
                ledgerKeys.put(step.ledgerKey(), result.ledgerId());
            }
            events.toolCall(id, step.tool(), step.params() == null ? Map.of() : step.params(),
                    result.summary(), result.ledgerId());
        }
        if (step.hypotheses() != null && !step.hypotheses().isEmpty()) {
            List<Map<String, Object>> hyps = new ArrayList<>();
            for (ScenarioDef.Hypothesis h : step.hypotheses()) {
                hyps.add(Map.of("text", h.text(), "probability", h.probability()));
            }
            events.hypotheses(id, hyps);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private AgentTools.ToolResult dispatch(String incidentId, ScenarioDef.ScriptStep step) {
        Map<String, Object> p = step.params() == null ? Map.of() : step.params();
        String inference = step.inference();
        return switch (step.tool()) {
            case "get_topology" -> tools.getTopology(incidentId, (String) p.get("flowId"), inference);
            case "query_logs" -> tools.queryLogs(incidentId, (String) p.get("serviceId"),
                    intOf(p.getOrDefault("minutes", 15)), inference);
            case "query_metrics" -> tools.queryMetrics(incidentId, (String) p.get("serviceId"), inference);
            case "get_change_events" -> tools.getChangeEvents(incidentId, (List<String>) p.get("services"),
                    intOf(p.getOrDefault("minutes", 60)), inference);
            case "compare_schema" -> tools.compareSchema(incidentId, (String) p.get("serviceId"), inference);
            case "search_incident_memory" -> tools.searchIncidentMemory(incidentId,
                    (String) p.get("symptoms"), inference);
            default -> throw new IllegalArgumentException("Unknown tool in script: " + step.tool());
        };
    }

    private TriageReport buildReport(Incident incident, ScenarioDef scenario, Map<String, Long> ledgerKeys,
                                     Instant start, boolean shortCircuited) {
        ScenarioDef.Report r = scenario.report();
        List<TriageReport.CausalLink> chain = new ArrayList<>();
        long lastLedgerId = ledger.forIncident(incident.getId()).isEmpty() ? 0
                : ledger.forIncident(incident.getId()).get(ledger.forIncident(incident.getId()).size() - 1).id();
        for (ScenarioDef.ChainLink link : r.causalChain()) {
            Long id = ledgerKeys.get(link.ledgerKey());
            if (id == null && shortCircuited) {
                // shortcut path may not have produced every citation; fall back to the memory-match entry
                id = lastLedgerId;
            }
            if (id != null) {
                chain.add(new TriageReport.CausalLink(link.claim(), id));
            }
        }
        var owner = topology.service(r.rootCauseService()).orElse(null);
        String summary = shortCircuited
                ? r.summary() + " (Diagnosed via incident memory: a matching past incident short-circuited the investigation.)"
                : r.summary();
        return new TriageReport(
                incident.getId(),
                r.rootCause(),
                r.confidence(),
                summary,
                chain,
                topology.blastRadius(r.rootCauseService()),
                owner == null ? "unknown" : owner.team(),
                owner == null ? "" : owner.slack(),
                r.remediation(),
                r.ruledOut() == null ? List.of() : r.ruledOut(),
                Duration.between(start, Instant.now()).toMillis(),
                incident.getToolCallCount(),
                redaction.count(incident.getId()),
                false);
    }

    private void rememberIncident(ScenarioDef scenario, TriageReport report) {
        memory.record(new IncidentMemoryService.PastIncident(
                report.incidentId(),
                Instant.now(),
                scenario.alertText(),
                "scripted:" + scenario.id(),
                report.rootCause(),
                String.join("; ", report.remediation())));
    }

    private double topSimilarity(AgentTools.ToolResult memoryResult) {
        if (!(memoryResult.detail() instanceof List<?> matches) || matches.isEmpty()) return 0;
        if (matches.get(0) instanceof IncidentMemoryService.Match m) return m.similarity();
        return 0;
    }

    private static int intOf(Object value) {
        return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private void pace() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(delayMinMs, delayMaxMs + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
