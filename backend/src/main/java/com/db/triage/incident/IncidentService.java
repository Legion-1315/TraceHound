package com.db.triage.incident;

import com.db.triage.agent.InvestigationEventPublisher;
import com.db.triage.agent.LlmInvestigator;
import com.db.triage.agent.ScriptedInvestigator;
import com.db.triage.history.IncidentHistoryService;
import com.db.triage.llm.LlmBudget;
import com.db.triage.model.ScenarioDef;
import com.db.triage.model.TriageReport;
import com.db.triage.sim.ScenarioService;
import com.db.triage.sim.SimulationEngine;
import com.db.triage.telemetry.TelemetryRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Starts investigations and runs them in the background.
 *
 * <ul>
 *   <li><b>Live estate</b> (TRIAGE_ESTATE=live): always the Claude agent, on any free-text alert. Needs
 *   ANTHROPIC_API_KEY; admission is subject to the {@link LlmBudget}.</li>
 *   <li><b>Simulated estate</b> (default): the original hackathon demo — a preset alert arms its scenario and
 *   the scripted replay runs, or the Claude agent when TRIAGE_MODE=llm.</li>
 * </ul>
 * A failed LLM run fails visibly; it never silently switches to the scripted replay.
 */
@Service
public class IncidentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

    /** Raised when an investigation cannot start (no API key configured, etc.). */
    public static class CannotStartException extends RuntimeException {
        public CannotStartException(String message) {
            super(message);
        }
    }

    private final ScriptedInvestigator scripted;
    private final LlmInvestigator llm;
    private final ScenarioService scenarios;
    private final SimulationEngine sim;
    private final IncidentHistoryService history;
    private final TelemetryRouter telemetry;
    private final LlmBudget budget;
    private final InvestigationEventPublisher events;
    private final String configuredMode;
    private final Map<String, Incident> incidents = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger(1);
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public IncidentService(ScriptedInvestigator scripted, LlmInvestigator llm, ScenarioService scenarios,
                           SimulationEngine sim, IncidentHistoryService history, TelemetryRouter telemetry,
                           LlmBudget budget, InvestigationEventPublisher events,
                           @Value("${triage.investigator-mode}") String configuredMode) {
        this.scripted = scripted;
        this.llm = llm;
        this.scenarios = scenarios;
        this.sim = sim;
        this.history = history;
        this.telemetry = telemetry;
        this.budget = budget;
        this.events = events;
        this.configuredMode = configuredMode;
    }

    /** Which investigator a new incident would get right now: "llm" or "scripted". */
    public String effectiveMode() {
        if (telemetry.isLive()) {
            return "llm";
        }
        return configuredMode.equalsIgnoreCase("llm") && llm.available() ? "llm" : "scripted";
    }

    public Incident start(String alertText, String clientKey) {
        String mode = effectiveMode();
        if (mode.equals("llm") && !llm.available()) {
            throw new CannotStartException("The live estate is investigated by the Claude agent, but no API key "
                    + "is configured. Put ANTHROPIC_API_KEY in the .env file at the repo root and restart.");
        }

        ScenarioDef scenario = null;
        if (!telemetry.isLive()) {
            // Simulated estate: exact preset match first, else the active fault, else the primary demo.
            scenario = scenarios.all().stream()
                    .filter(s -> alertText.strip().equals(s.alertText().strip()))
                    .findFirst()
                    .orElseGet(() -> sim.activeScenario()
                            .orElseGet(() -> scenarios.byId("schema-drift").orElseThrow()));
            // Pasting a preset alert arms its fault if the estate is still healthy or on another scenario.
            String scenarioId = scenario.id();
            if (sim.activeScenario().map(a -> !a.id().equals(scenarioId)).orElse(true)) {
                sim.activate(scenario);
            }
        }

        // Admission control for paid model calls (throws BudgetExceededException when over a limit).
        LlmBudget.Permit permit = mode.equals("llm") ? budget.admit(clientKey) : null;

        String id = "INC-" + (1000 + counter.getAndIncrement());
        Incident incident = new Incident(id, alertText, scenario == null ? "" : scenario.id(), mode);
        incidents.put(id, incident);
        ScenarioDef chosen = scenario;
        executor.submit(() -> run(incident, chosen, permit));
        return incident;
    }

    private void run(Incident incident, ScenarioDef scenario, LlmBudget.Permit permit) {
        try {
            TriageReport report = incident.getMode().equals("llm")
                    ? llm.investigate(incident)
                    : scripted.investigate(incident, scenario);
            incident.setReport(report);
            incident.setStatus(Incident.Status.COMPLETE);
            // Append to the historical corpus that powers the Analytics dashboards.
            history.record(incident.getId(), incident.getScenarioId(), report, incident.getAlertText());
        } catch (Exception e) {
            log.warn("Investigation {} failed: {}", incident.getId(), e.getMessage());
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            incident.fail(message);
            events.error(incident.getId(), message);
        } finally {
            if (permit != null) {
                permit.close();
            }
        }
    }

    public Optional<Incident> byId(String id) {
        return Optional.ofNullable(incidents.get(id));
    }
}
