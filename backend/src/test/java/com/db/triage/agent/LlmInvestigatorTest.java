package com.db.triage.agent;

import com.db.triage.incident.Incident;
import com.db.triage.incident.IncidentService;
import com.db.triage.ledger.EvidenceLedger;
import com.db.triage.llm.LlmClient;
import com.db.triage.model.TriageReport;
import com.db.triage.sim.ScenarioService;
import com.db.triage.sim.SimulationEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Map;

import static com.db.triage.agent.FakeLlmClient.blocks;
import static com.db.triage.agent.FakeLlmClient.text;
import static com.db.triage.agent.FakeLlmClient.toolUse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The real agent loop against a scripted fake model and the simulated estate. */
@SpringBootTest(properties = {
        "triage.memory-file=build/test-data/llm-incident-memory.json",
        "triage.history-file=build/test-data/llm-incident-history.json",
        "triage.investigator-mode=llm",
        "triage.llm.max-investigations-per-client-per-hour=100",
        "triage.llm.max-investigations-per-day=1000",
        "triage.llm.max-daily-cost-usd=1000"
})
class LlmInvestigatorTest {

    @TestConfiguration
    static class FakeModel {
        @Bean
        @Primary
        FakeLlmClient fakeLlmClient() {
            return new FakeLlmClient();
        }
    }

    @Autowired
    FakeLlmClient model;
    @Autowired
    LlmInvestigator investigator;
    @Autowired
    IncidentService incidents;
    @Autowired
    SimulationEngine sim;
    @Autowired
    ScenarioService scenarios;
    @Autowired
    EvidenceLedger ledger;
    @Autowired
    InvestigationEventPublisher events;

    @BeforeEach
    void schemaDriftIsActive() {
        sim.activate(scenarios.byId("schema-drift").orElseThrow());
    }

    @Test
    void aBlankRootCauseFallsBackToTheSummary() throws Exception {
        // qwen3:4b did this in a real run: the conclusion only in "summary", root_cause empty
        model.script(turn -> {
            ArrayNode b = blocks();
            ObjectNode r = toolUse(b, "submit_report");
            r.put("outcome", "ROOT_CAUSE_FOUND");
            r.put("root_cause", "");
            r.put("root_cause_service", "ref-data-service");
            r.put("confidence", 90);
            r.put("summary", "Ref-data 2.4.1 removed the 'ccy' field.");
            r.putArray("causal_chain");
            r.putArray("remediation");
            r.putArray("ruled_out");
            return b;
        });
        TriageReport report = investigator.investigate(new Incident("INC-LLM-5", "gateway errors", "", "llm"));
        assertEquals("Ref-data 2.4.1 removed the 'ccy' field.", report.rootCause());
        // the canvas marks the root cause even though the model never called set_node_status
        assertTrue(events.historyFor("INC-LLM-5").stream().anyMatch(e -> e.toString().contains("ROOT_CAUSE")
                && e.toString().contains("ref-data-service")));
    }

    @Test
    void investigatesWithToolsAndReportsOnlyCitationsThatExist() throws Exception {
        model.script(turn -> {
            ArrayNode b = blocks();
            switch (turn.number()) {
                case 1 -> {
                    text(b, "Gateway validation failures on the MiFID flow; pulling the subgraph.");
                    toolUse(b, "get_topology").put("flowId", "mifid-reporting");
                    ObjectNode s = toolUse(b, "set_node_status");
                    s.put("service", "regulatory-gateway");
                    s.put("status", "PROBING");
                }
                case 2 -> toolUse(b, "query_logs").put("serviceId", "regulatory-gateway");
                case 3 -> toolUse(b, "compare_schema").put("serviceId", "ref-data-service");
                default -> {
                    long schemaId = turn.ledgerIdsSoFar().get(turn.ledgerIdsSoFar().size() - 1);
                    long logsId = turn.ledgerIdsSoFar().get(turn.ledgerIdsSoFar().size() - 2);
                    ObjectNode r = toolUse(b, "submit_report");
                    r.put("outcome", "ROOT_CAUSE_FOUND");
                    r.put("root_cause", "ref-data 2.4.1 renamed ccy to currencyCode");
                    r.put("root_cause_service", "ref-data-service");
                    r.put("confidence", 90);
                    r.put("summary", "Schema drift.");
                    ArrayNode chain = r.putArray("causal_chain");
                    chain.addObject().put("claim", "schema changed").put("ledger_id", schemaId);
                    chain.addObject().put("claim", "gateway rejects").put("ledger_id", logsId);
                    chain.addObject().put("claim", "made up").put("ledger_id", 987654321L);
                    r.putArray("remediation").add("Roll back 2.4.1 (awaiting approval)");
                    r.putArray("ruled_out").add("trade-capture healthy");
                }
            }
            return b;
        });

        Incident incident = new Incident("INC-LLM-1", "regulatory-gateway validation failures", "", "llm");
        TriageReport report = investigator.investigate(incident);

        assertEquals(TriageReport.ROOT_CAUSE_FOUND, report.outcome());
        assertEquals("ref-data-service", report.rootCauseService());
        assertEquals("Reference Data Platform", report.owningTeam());
        assertEquals("llm", report.mode());
        assertEquals(3, report.toolCallCount());
        assertEquals(2, report.causalChain().size(), "the invented ledger id must be dropped");
        report.causalChain().forEach(l -> assertTrue(ledger.exists("INC-LLM-1", l.ledgerId())));
        assertTrue(report.blastRadius().stream().anyMatch(b -> b.flowId().equals("confirmations")));
        assertEquals(4, report.usage().apiCalls());
        assertEquals(4000, report.usage().inputTokens());

        List<Map<String, Object>> evs = events.historyFor("INC-LLM-1");
        assertTrue(evs.stream().anyMatch(e -> "TOOL_CALL".equals(e.get("type"))));
        assertTrue(evs.stream().anyMatch(e -> "NODE_STATUS".equals(e.get("type"))));
        assertTrue(evs.stream().anyMatch(e -> "USAGE".equals(e.get("type"))));
        assertTrue(evs.stream().anyMatch(e -> "AGENT_THOUGHT".equals(e.get("type"))));

        // prompt caching: system, last tool and the latest message carry cache_control
        ObjectNode first = model.requests.get(0);
        assertEquals("ephemeral", first.path("system").get(0).path("cache_control").path("type").asText());
        JsonNode toolDefs = first.path("tools");
        assertEquals("ephemeral", toolDefs.get(toolDefs.size() - 1).path("cache_control").path("type").asText());
        assertTrue(first.path("system").get(0).path("text").asText().contains("ref-data-service"));
    }

    @Test
    void outOfScopeAlertIsNotInvestigated() throws Exception {
        model.script(turn -> {
            ArrayNode b = blocks();
            ObjectNode r = toolUse(b, "submit_report");
            r.put("outcome", "OUT_OF_SCOPE");
            r.put("root_cause", "The alert is about a printer; this estate covers MiFID reporting services.");
            r.put("root_cause_service", "none");
            r.put("confidence", 95);
            r.put("summary", "Out of scope.");
            r.putArray("causal_chain");
            r.putArray("remediation");
            r.putArray("ruled_out");
            return b;
        });
        TriageReport report = investigator.investigate(new Incident("INC-LLM-2", "printer on floor 3 is jammed", "", "llm"));
        assertEquals(TriageReport.OUT_OF_SCOPE, report.outcome());
        assertNull(report.rootCauseService());
        assertTrue(report.blastRadius().isEmpty());
        assertFalse(report.inconclusive());
        assertEquals(0, report.toolCallCount());
    }

    @Test
    void toolBudgetIsEnforcedAndTheReportIsForced() throws Exception {
        model.script(turn -> {
            ArrayNode b = blocks();
            boolean forced = turn.request().toString().contains(LlmInvestigator.FORCE_REPORT);
            if (!forced) {
                toolUse(b, "query_metrics").put("serviceId", "trade-capture");
            } else {
                ObjectNode r = toolUse(b, "submit_report");
                r.put("outcome", "INCONCLUSIVE");
                r.put("root_cause", "Ran out of budget");
                r.put("root_cause_service", "none");
                r.put("confidence", 10);
                r.put("summary", "Inconclusive.");
                r.putArray("causal_chain");
                r.putArray("remediation");
                r.putArray("ruled_out");
            }
            return b;
        });
        TriageReport report = investigator.investigate(new Incident("INC-LLM-3", "something is slow", "", "llm"));
        assertEquals(LlmInvestigator.MAX_TOOL_CALLS, report.toolCallCount());
        assertEquals(TriageReport.INCONCLUSIVE, report.outcome());
        assertTrue(report.inconclusive());
        // the wrap-up is asked for in the conversation: forced tool_choice is a 400 on newer Claude models
        assertTrue(model.requests.stream().anyMatch(r -> r.toString().contains(LlmInvestigator.FORCE_REPORT)));
        assertTrue(model.requests.stream().noneMatch(r -> r.has("tool_choice")));
    }

    @Test
    void modelFailureFailsTheIncidentVisiblyWithoutScriptedFallback() throws Exception {
        model.script(turn -> blocks());
        model.failWith(new LlmClient.LlmException(529, "Anthropic API overloaded (529)."));

        Incident incident = incidents.start("ALERT [SEV2] regulatory-gateway: error rate 34%", "test-client");
        assertEquals("llm", incident.getMode());
        for (int i = 0; i < 100 && incident.getStatus() == Incident.Status.RUNNING; i++) {
            Thread.sleep(50);
        }
        assertEquals(Incident.Status.FAILED, incident.getStatus());
        assertNull(incident.getReport(), "no scripted report may be substituted");
        assertTrue(incident.getError().contains("529"));
        assertTrue(events.historyFor(incident.getId()).stream().anyMatch(e -> "ERROR".equals(e.get("type"))));
    }
}
