package com.db.triage.api;

import com.db.triage.agent.InvestigationEventPublisher;
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

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiControllers {

    private final TopologyService topology;
    private final IncidentService incidents;
    private final EvidenceLedger ledger;
    private final ScenarioService scenarios;
    private final SimulationEngine sim;
    private final InvestigationEventPublisher events;

    public ApiControllers(TopologyService topology, IncidentService incidents, EvidenceLedger ledger,
                          ScenarioService scenarios, SimulationEngine sim, InvestigationEventPublisher events) {
        this.topology = topology;
        this.incidents = incidents;
        this.ledger = ledger;
        this.scenarios = scenarios;
        this.sim = sim;
        this.events = events;
    }

    @GetMapping("/topology")
    public Object getTopology() {
        return topology.topology();
    }

    public record StartIncidentRequest(String alertText) {
    }

    @PostMapping("/incidents")
    public Map<String, Object> startIncident(@RequestBody StartIncidentRequest req) {
        if (req.alertText() == null || req.alertText().isBlank()) {
            throw new ResponseStatusExceptionAdapter(HttpStatus.BAD_REQUEST, "alertText is required");
        }
        Incident incident = incidents.start(req.alertText());
        return Map.of("incidentId", incident.getId(), "mode", incident.getMode(),
                "scenarioId", incident.getScenarioId());
    }

    @GetMapping("/incidents/{id}")
    public ResponseEntity<?> incident(@PathVariable String id) {
        return incidents.byId(id)
                .<ResponseEntity<?>>map(i -> ResponseEntity.ok(Map.of(
                        "incidentId", i.getId(),
                        "status", i.getStatus().name(),
                        "mode", i.getMode(),
                        "startedAt", i.getStartedAt().toString(),
                        "toolCalls", i.getToolCallCount())))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/incidents/{id}/report")
    public ResponseEntity<?> report(@PathVariable String id) {
        return incidents.byId(id)
                .<ResponseEntity<?>>map(i -> i.getReport() == null
                        ? ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body(Map.of("status", i.getStatus().name()))
                        : ResponseEntity.ok(i.getReport()))
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

    @GetMapping("/chaos")
    public Map<String, Object> chaosState() {
        List<Map<String, String>> list = scenarios.all().stream()
                .map(s -> Map.of("id", s.id(), "name", s.name(),
                        "description", s.description() == null ? "" : s.description(),
                        "alertText", s.alertText()))
                .toList();
        return Map.of("scenarios", list,
                "active", sim.activeScenario().map(ScenarioDef::id).orElse(""));
    }

    @PostMapping("/chaos/{scenarioId}")
    public Map<String, String> activate(@PathVariable String scenarioId) {
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

    static class ResponseStatusExceptionAdapter extends org.springframework.web.server.ResponseStatusException {
        ResponseStatusExceptionAdapter(HttpStatus status, String reason) {
            super(status, reason);
        }
    }
}
