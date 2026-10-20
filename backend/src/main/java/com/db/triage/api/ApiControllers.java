package com.db.triage.api;

import com.db.triage.agent.InvestigationEventPublisher;
import com.db.triage.agent.LlmInvestigator;
import com.db.triage.alerts.AlertMonitor;
import com.db.triage.live.LiveChaosService;
import com.db.triage.live.LiveFaultCatalog;
import com.db.triage.llm.LlmBudget;
import com.db.triage.telemetry.LiveEstateClient;
import com.db.triage.telemetry.TelemetryRouter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import com.db.triage.history.DashboardResponse;
import com.db.triage.history.IncidentHistory;
import com.db.triage.history.IncidentHistoryService;
import com.db.triage.incident.Incident;
import com.db.triage.incident.IncidentService;
import com.db.triage.ledger.EvidenceLedger;
import com.db.triage.model.ScenarioDef;
import com.db.triage.sim.ScenarioService;
import com.db.triage.sim.SimulationEngine;
import com.db.triage.topology.TopologyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiControllers {

    static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    private final TopologyService topology;
    private final IncidentService incidents;
    private final EvidenceLedger ledger;
    private final ScenarioService scenarios;
    private final SimulationEngine sim;
    private final InvestigationEventPublisher events;
    private final IncidentHistoryService history;
    private final TelemetryRouter telemetry;
    private final LiveEstateClient estate;
    private final LiveFaultCatalog liveFaults;
    private final LiveChaosService liveChaos;
    private final AlertMonitor alerts;
    private final LlmInvestigator llm;
    private final LlmBudget budget;
    private final boolean trustForwardedFor;
    private final String adminToken;

    public ApiControllers(TopologyService topology, IncidentService incidents, EvidenceLedger ledger,
                          ScenarioService scenarios, SimulationEngine sim, InvestigationEventPublisher events,
                          IncidentHistoryService history, TelemetryRouter telemetry, LiveEstateClient estate,
                          LiveFaultCatalog liveFaults, LiveChaosService liveChaos, AlertMonitor alerts,
                          LlmInvestigator llm, LlmBudget budget,
                          @Value("${triage.trust-forwarded-for:false}") boolean trustForwardedFor,
                          @Value("${triage.admin-token:}") String adminToken) {
        this.topology = topology;
        this.incidents = incidents;
        this.ledger = ledger;
        this.scenarios = scenarios;
        this.sim = sim;
        this.events = events;
        this.history = history;
        this.telemetry = telemetry;
        this.estate = estate;
        this.liveFaults = liveFaults;
        this.liveChaos = liveChaos;
        this.alerts = alerts;
        this.llm = llm;
        this.budget = budget;
        this.trustForwardedFor = trustForwardedFor;
        this.adminToken = adminToken == null ? "" : adminToken.trim();
    }

    /** What the UI needs to label itself honestly: which estate, which investigator, which model. */
    @GetMapping("/mode")
    public Map<String, Object> mode() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("estate", telemetry.kind());
        out.put("investigator", incidents.effectiveMode());
        out.put("llmConfigured", llm.available());
        out.put("provider", llm.provider());
        out.put("model", llm.available() ? llm.model() : null);
        out.put("budget", budget.status());
        return out;
    }

    /** Live estate: alerts currently firing from the metrics monitor. */
    @GetMapping("/alerts")
    public List<AlertMonitor.Alert> alerts() {
        return alerts.activeAlerts();
    }

    /** Live estate: per-service health. */
    @GetMapping("/estate/health")
    public Map<String, Object> estateHealth() {
        if (!telemetry.isLive()) {
            return Map.of("estate", "simulated");
        }
        return estate.healthOfAll();
    }

    @GetMapping("/topology")
    public Object getTopology() {
        return topology.topology();
    }

    public record StartIncidentRequest(String alertText) {
    }

    @PostMapping("/incidents")
    public ResponseEntity<Map<String, Object>> startIncident(@RequestBody StartIncidentRequest req,
                                                             HttpServletRequest http) {
        if (req.alertText() == null || req.alertText().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "alertText is required"));
        }
        if (req.alertText().length() > 4000) {
            return ResponseEntity.badRequest().body(Map.of("error", "alertText is limited to 4000 characters"));
        }
        try {
            Incident incident = incidents.start(req.alertText(), clientKey(http));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("incidentId", incident.getId());
            body.put("mode", incident.getMode());
            body.put("scenarioId", incident.getScenarioId());
            body.put("estate", telemetry.kind());
            body.put("model", incident.getMode().equals("llm") ? llm.model() : null);
            return ResponseEntity.ok(body);
        } catch (LlmBudget.BudgetExceededException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", e.getMessage()));
        } catch (IncidentService.CannotStartException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
        }
    }

    private String clientKey(HttpServletRequest http) {
        if (trustForwardedFor) {
            String fwd = http.getHeader("X-Forwarded-For");
            if (fwd != null && !fwd.isBlank()) {
                return fwd.split(",")[0].trim();
            }
        }
        return http.getRemoteAddr();
    }

    @GetMapping("/incidents/{id}")
    public ResponseEntity<?> incident(@PathVariable String id) {
        return incidents.byId(id)
                .<ResponseEntity<?>>map(i -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("incidentId", i.getId());
                    body.put("status", i.getStatus().name());
                    body.put("mode", i.getMode());
                    body.put("startedAt", i.getStartedAt().toString());
                    body.put("toolCalls", i.getToolCallCount());
                    body.put("usage", i.getUsage());
                    body.put("error", i.getError());
                    return ResponseEntity.ok(body);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/incidents/{id}/report")
    public ResponseEntity<?> report(@PathVariable String id) {
        return incidents.byId(id)
                .<ResponseEntity<?>>map(i -> {
                    if (i.getReport() != null) {
                        return ResponseEntity.ok(i.getReport());
                    }
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("status", i.getStatus().name());
                    body.put("error", i.getError());
                    return ResponseEntity.status(i.getStatus() == Incident.Status.FAILED
                            ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.ACCEPTED).body(body);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/incidents/{id}/ledger")
    public ResponseEntity<?> ledger(@PathVariable String id) {
        return incidents.byId(id)
                .<ResponseEntity<?>>map(i -> ResponseEntity.ok(ledger.forIncident(id)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/incidents/{id}/events")
    public ResponseEntity<?> replayEvents(@PathVariable String id) {
        return incidents.byId(id)
                .<ResponseEntity<?>>map(i -> ResponseEntity.ok(events.historyFor(id)))
                .orElse(ResponseEntity.notFound().build());
    }

    /** Aggregated dashboards for the Analytics tab. */
    @GetMapping("/analytics")
    public DashboardResponse analytics() {
        return history.dashboard();
    }

    /** The raw historical incident corpus (seeded + every completed investigation). */
    @GetMapping("/history")
    public List<IncidentHistory> history() {
        return history.all();
    }

    @GetMapping("/chaos")
    public Map<String, Object> chaosState() {
        if (telemetry.isLive()) {
            // Live faults are injected into real services; alerts come from the monitor, so there are no
            // preset alert texts to paste.
            List<Map<String, String>> list = liveFaults.all().stream()
                    .map(f -> Map.of("id", f.id(), "name", f.name(), "description", f.description(),
                            "category", f.category(), "alertText", ""))
                    .toList();
            List<String> active;
            try {
                active = liveChaos.activeFaults();
            } catch (RuntimeException e) {
                active = List.of();
            }
            return Map.of("estate", "live", "scenarios", list, "active", String.join(",", active),
                    "activeFaults", active);
        }
        List<Map<String, String>> list = scenarios.all().stream()
                .map(s -> Map.of("id", s.id(), "name", s.name(),
                        "description", s.description() == null ? "" : s.description(),
                        "alertText", s.alertText()))
                .toList();
        String active = sim.activeScenario().map(ScenarioDef::id).orElse("");
        return Map.of("estate", "simulated", "scenarios", list, "active", active,
                "activeFaults", active.isEmpty() ? List.of() : List.of(active));
    }

    @PostMapping("/chaos/{scenarioId}")
    public Map<String, Object> activate(@PathVariable String scenarioId) {
        if (telemetry.isLive()) {
            if (scenarioId.equals("none")) {
                Map<String, String> errors = liveChaos.resetAll();
                return Map.of("active", "", "errors", errors);
            }
            LiveFaultCatalog.Fault fault = liveFaults.byId(scenarioId)
                    .orElseThrow(() -> new ResponseStatusExceptionAdapter(HttpStatus.NOT_FOUND,
                            "Unknown fault " + scenarioId));
            try {
                liveChaos.inject(fault);
            } catch (RuntimeException e) {
                throw new ResponseStatusExceptionAdapter(HttpStatus.BAD_GATEWAY,
                        "Could not inject " + scenarioId + ": " + e.getMessage());
            }
            return Map.of("active", fault.id());
        }
        if (scenarioId.equals("none")) {
            sim.deactivate();
            return Map.of("active", "");
        }
        ScenarioDef def = scenarios.byId(scenarioId)
                .orElseThrow(() -> new ResponseStatusExceptionAdapter(HttpStatus.NOT_FOUND,
                        "Unknown scenario " + scenarioId));
        sim.activate(def);
        return Map.of("active", def.id());
    }

    /**
     * Live estate: wipe logs, metrics and change history on every service (used by the eval harness).
     * On a public demo this would let any visitor erase evidence mid-investigation, so it needs the
     * {@code TRIAGE_ADMIN_TOKEN} in an {@code X-Admin-Token} header, and does not exist when no token is set.
     */
    @PostMapping("/chaos/admin/clear-history")
    public Map<String, Object> clearHistory(@RequestHeader(value = ADMIN_TOKEN_HEADER, required = false) String token) {
        if (adminToken.isEmpty()) {
            throw new ResponseStatusExceptionAdapter(HttpStatus.NOT_FOUND, "Not found");
        }
        if (token == null || !MessageDigest.isEqual(adminToken.getBytes(StandardCharsets.UTF_8),
                token.trim().getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusExceptionAdapter(HttpStatus.UNAUTHORIZED, "Missing or wrong " + ADMIN_TOKEN_HEADER);
        }
        if (!telemetry.isLive()) {
            throw new ResponseStatusExceptionAdapter(HttpStatus.BAD_REQUEST, "Only available for the live estate");
        }
        return Map.of("errors", liveChaos.clearHistoryAll());
    }

    static class ResponseStatusExceptionAdapter extends org.springframework.web.server.ResponseStatusException {
        ResponseStatusExceptionAdapter(HttpStatus status, String reason) {
            super(status, reason);
        }
    }
}
