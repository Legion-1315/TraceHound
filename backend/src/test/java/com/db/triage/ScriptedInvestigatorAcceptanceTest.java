package com.db.triage;

import com.db.triage.agent.ScriptedInvestigator;
import com.db.triage.incident.Incident;
import com.db.triage.ledger.EvidenceLedger;
import com.db.triage.model.ScenarioDef;
import com.db.triage.model.TriageReport;
import com.db.triage.sim.ScenarioService;
import com.db.triage.sim.SimulationEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance: for each scenario the scripted investigator lands on the expected root cause,
 * and every report citation resolves to a real evidence-ledger entry.
 */
@SpringBootTest(properties = {
        "triage.step-delay-min-ms=1",
        "triage.step-delay-max-ms=2",
        "triage.memory-file=build/test-data/incident-memory.json",
        "triage.history-file=build/test-data/incident-history.json"
})
class ScriptedInvestigatorAcceptanceTest {

    @Autowired
    ScriptedInvestigator investigator;
    @Autowired
    ScenarioService scenarios;
    @Autowired
    SimulationEngine sim;
    @Autowired
    EvidenceLedger ledger;

    @Test
    void schemaDriftDiagnosesRefDataSchemaChange() {
        TriageReport report = run("schema-drift", "INC-T1");
        assertTrue(report.rootCause().toLowerCase().contains("ref-data"), report.rootCause());
        assertTrue(report.rootCause().contains("ccy"), report.rootCause());
        assertEquals("Reference Data Platform", report.owningTeam());
        // blast radius must include the second flow that consumes ref-data
        assertTrue(report.blastRadius().stream().anyMatch(b -> b.flowId().equals("confirmations")),
                "expected confirmations flow in blast radius");
        assertCitationsResolve("INC-T1", report);
    }

    @Test
    void consumerLagDiagnosesEnrichmentStall() {
        TriageReport report = run("consumer-lag", "INC-T2");
        assertTrue(report.rootCause().toLowerCase().contains("enrichment"), report.rootCause());
        assertTrue(report.rootCause().toLowerCase().contains("stall"), report.rootCause());
        assertCitationsResolve("INC-T2", report);
    }

    @Test
    void certExpiryDiagnosesGatewayLocalFault() {
        TriageReport report = run("cert-expiry", "INC-T3");
        assertTrue(report.rootCause().toLowerCase().contains("certificate"), report.rootCause());
        assertTrue(report.rootCause().toLowerCase().contains("gateway"), report.rootCause());
        // gateway-local: blast radius should NOT implicate the confirmations flow
        assertTrue(report.blastRadius().stream().noneMatch(b -> b.flowId().equals("confirmations")));
        assertCitationsResolve("INC-T3", report);
    }

    @Test
    void latencySpikeDiagnosesRefDataIndexRebuild() {
        TriageReport report = run("latency-spike", "INC-T4");
        String rc = report.rootCause().toLowerCase();
        assertTrue(rc.contains("ref-data"), report.rootCause());
        assertTrue(rc.contains("index"), report.rootCause());
        assertEquals("Reference Data Platform", report.owningTeam());
        assertCitationsResolve("INC-T4", report);
    }

    @Test
    void duplicateTradesDiagnosesCaptureSideDuplication() {
        TriageReport report = run("duplicate-trades", "INC-T5");
        String rc = report.rootCause().toLowerCase();
        assertTrue(rc.contains("trade-capture"), report.rootCause());
        assertTrue(rc.contains("duplicate"), report.rootCause());
        // the fault is at ingestion, not at the gateway that detected it
        assertEquals("Front Office Integration", report.owningTeam());
        assertCitationsResolve("INC-T5", report);
    }

    @Test
    void threadPoolExhaustionDiagnosesBuilderResourceLimit() {
        TriageReport report = run("thread-pool-exhaustion", "INC-T6");
        String rc = report.rootCause().toLowerCase();
        assertTrue(rc.contains("submission-builder"), report.rootCause());
        assertTrue(rc.contains("pool"), report.rootCause());
        assertCitationsResolve("INC-T6", report);
    }

    @Test
    void everyScenarioDeclaresACategoryForAnalytics() {
        for (ScenarioDef def : scenarios.all()) {
            assertNotNull(def.category(), "scenario " + def.id() + " must declare a category");
            assertFalse(def.category().isBlank(), "scenario " + def.id() + " category must not be blank");
            assertNotNull(def.report().rootCauseService(),
                    "scenario " + def.id() + " must declare a root_cause_service");
        }
    }

    private TriageReport run(String scenarioId, String incidentId) {
        ScenarioDef scenario = scenarios.byId(scenarioId).orElseThrow();
        sim.activate(scenario);
        Incident incident = new Incident(incidentId, scenario.alertText(), scenarioId, "scripted");
        return investigator.investigate(incident, scenario);
    }

    private void assertCitationsResolve(String incidentId, TriageReport report) {
        List<TriageReport.CausalLink> chain = report.causalChain();
        assertFalse(chain.isEmpty(), "causal chain must not be empty");
        for (TriageReport.CausalLink link : chain) {
            assertTrue(ledger.exists(incidentId, link.ledgerId()),
                    "citation " + link.ledgerId() + " missing from ledger: " + link.claim());
        }
    }
}
