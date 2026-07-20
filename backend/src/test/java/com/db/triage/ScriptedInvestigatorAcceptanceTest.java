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
        "triage.memory-file=build/test-data/incident-memory.json"
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
