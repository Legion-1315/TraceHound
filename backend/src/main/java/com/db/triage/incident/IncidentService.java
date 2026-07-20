package com.db.triage.incident;

import com.db.triage.agent.LlmInvestigator;
import com.db.triage.agent.ScriptedInvestigator;
import com.db.triage.model.ScenarioDef;
import com.db.triage.model.TriageReport;
import com.db.triage.sim.ScenarioService;
import com.db.triage.sim.SimulationEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class IncidentService {

    private final ScriptedInvestigator scripted;
    private final LlmInvestigator llm;
    private final ScenarioService scenarios;
    private final SimulationEngine sim;
    private final String configuredMode;
    private final Map<String, Incident> incidents = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger(1);
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public IncidentService(ScriptedInvestigator scripted, LlmInvestigator llm, ScenarioService scenarios,
                           SimulationEngine sim, @Value("${triage.investigator-mode}") String configuredMode) {
        this.scripted = scripted;
        this.llm = llm;
        this.scenarios = scenarios;
        this.sim = sim;
        this.configuredMode = configuredMode;
    }

    public Incident start(String alertText) {
        // Resolve which scenario this alert belongs to: exact preset match first, else the active fault.
        ScenarioDef scenario = scenarios.all().stream()
                .filter(s -> alertText.strip().equals(s.alertText().strip()))
                .findFirst()
                .orElseGet(() -> sim.activeScenario()
                        .orElseGet(() -> scenarios.byId("schema-drift").orElseThrow()));
        // Pasting a preset alert arms its fault if the estate is still healthy or on another scenario.
        if (sim.activeScenario().map(a -> !a.id().equals(scenario.id())).orElse(true)) {
            sim.activate(scenario);
        }

        boolean useLlm = configuredMode.equalsIgnoreCase("llm") && llm.available();
        String mode = useLlm ? "llm" : "scripted";
        String id = "INC-" + (1000 + counter.getAndIncrement());
        Incident incident = new Incident(id, alertText, scenario.id(), mode);
        incidents.put(id, incident);
        executor.submit(() -> run(incident, scenario, useLlm));
        return incident;
    }

    private void run(Incident incident, ScenarioDef scenario, boolean useLlm) {
        try {
            TriageReport report = useLlm
                    ? llm.investigate(incident, scenario)
                    : scripted.investigate(incident, scenario);
            incident.setReport(report);
            incident.setStatus(Incident.Status.COMPLETE);
        } catch (Exception e) {
            // LLM mode must never strand the demo: fall back to the scripted investigator.
            if (useLlm) {
                try {
                    TriageReport report = scripted.investigate(incident, scenario);
                    incident.setReport(report);
                    incident.setStatus(Incident.Status.COMPLETE);
                    return;
                } catch (Exception ignored) {
                }
            }
            incident.setStatus(Incident.Status.FAILED);
        }
    }

    public Optional<Incident> byId(String id) {
        return Optional.ofNullable(incidents.get(id));
    }
}
