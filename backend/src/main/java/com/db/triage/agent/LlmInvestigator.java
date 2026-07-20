package com.db.triage.agent;

import com.db.triage.incident.Incident;
import com.db.triage.ledger.EvidenceLedger;
import com.db.triage.memory.IncidentMemoryService;
import com.db.triage.model.ScenarioDef;
import com.db.triage.model.TriageReport;
import com.db.triage.redaction.RedactionService;
import com.db.triage.topology.TopologyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Live LLM agent mode: an Anthropic tool-use loop over the same read-only tools as the
 * scripted mode, emitting the same WebSocket events. Requires ANTHROPIC_API_KEY.
 */
@Service
public class LlmInvestigator {

    private static final int MAX_TOOL_CALLS = 12;

    private final AgentTools tools;
    private final InvestigationEventPublisher events;
    private final TopologyService topology;
    private final EvidenceLedger ledger;
    private final RedactionService redaction;
    private final IncidentMemoryService memory;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final String apiKey;
    private final String model;

    public LlmInvestigator(AgentTools tools, InvestigationEventPublisher events, TopologyService topology,
                           EvidenceLedger ledger, RedactionService redaction, IncidentMemoryService memory,
                           @Value("${triage.anthropic.api-key}") String apiKey,
                           @Value("${triage.anthropic.model}") String model) {
        this.tools = tools;
        this.events = events;
        this.topology = topology;
        this.ledger = ledger;
        this.redaction = redaction;
        this.memory = memory;
        this.apiKey = apiKey;
        this.model = model;
    }

    public boolean available() {
        return apiKey != null && !apiKey.isBlank();
    }

    public TriageReport investigate(Incident incident, ScenarioDef scenario) throws Exception {
        Instant start = Instant.now();
        String incidentId = incident.getId();
        ArrayNode messages = json.createArrayNode();
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", "Investigate this production alert:\n\n" + incident.getAlertText());

        ObjectNode reportNode = null;
        int toolCalls = 0;

        while (toolCalls <= MAX_TOOL_CALLS && reportNode == null) {
            JsonNode response = callAnthropic(messages);
            JsonNode content = response.path("content");

            ObjectNode assistantMsg = json.createObjectNode();
            assistantMsg.put("role", "assistant");
            assistantMsg.set("content", content);
            messages.add(assistantMsg);

            ArrayNode toolResults = json.createArrayNode();
            for (JsonNode block : content) {
                String type = block.path("type").asText();
                if (type.equals("text")) {
                    String text = block.path("text").asText().trim();
                    if (!text.isEmpty()) {
                        for (String line : text.split("\n")) {
                            if (!line.isBlank()) events.thought(incidentId, line.trim());
                        }
                    }
                } else if (type.equals("tool_use")) {
                    String name = block.path("name").asText();
                    JsonNode input = block.path("input");
                    String toolUseId = block.path("id").asText();
                    String resultText;
                    if (name.equals("submit_report")) {
                        reportNode = (ObjectNode) input;
                        resultText = "Report received.";
                    } else if (name.equals("set_node_status")) {
                        events.nodeStatus(incidentId, input.path("service").asText(),
                                input.path("status").asText());
                        resultText = "ok";
                    } else if (name.equals("update_hypotheses")) {
                        List<Map<String, Object>> hyps = new ArrayList<>();
                        for (JsonNode h : input.path("hypotheses")) {
                            hyps.add(Map.of("text", h.path("text").asText(),
                                    "probability", h.path("probability").asInt()));
                        }
                        events.hypotheses(incidentId, hyps);
                        resultText = "ok";
                    } else {
                        AgentTools.ToolResult tr = dispatch(incidentId, name, input);
                        toolCalls++;
                        incident.incrementToolCalls();
                        events.toolCall(incidentId, name, json.convertValue(input, Map.class),
                                tr.summary(), tr.ledgerId());
                        resultText = "[ledger #" + tr.ledgerId() + "] " + tr.summary()
                                + "\n\nDetail:\n" + json.writeValueAsString(tr.detail());
                        if (resultText.length() > 6000) {
                            resultText = resultText.substring(0, 6000) + "…(truncated)";
                        }
                    }
                    ObjectNode result = toolResults.addObject();
                    result.put("type", "tool_result");
                    result.put("tool_use_id", toolUseId);
                    result.put("content", resultText);
                }
            }

            if (toolResults.isEmpty()) {
                break; // model stopped without submitting a report
            }
            ObjectNode toolMsg = messages.addObject();
            toolMsg.put("role", "user");
            toolMsg.set("content", toolResults);
        }

        TriageReport report = buildReport(incident, scenario, reportNode, start);
        events.reportReady(incidentId);
        memory.record(new IncidentMemoryService.PastIncident(incidentId, Instant.now(),
                incident.getAlertText(), "llm", report.rootCause(), String.join("; ", report.remediation())));
        return report;
    }

    private AgentTools.ToolResult dispatch(String incidentId, String name, JsonNode input) {
        return switch (name) {
            case "get_topology" -> tools.getTopology(incidentId,
                    input.hasNonNull("flowId") ? input.get("flowId").asText() : null, "");
            case "query_logs" -> tools.queryLogs(incidentId, input.path("serviceId").asText(),
                    input.path("minutes").asInt(15), "");
            case "query_metrics" -> tools.queryMetrics(incidentId, input.path("serviceId").asText(), "");
            case "get_change_events" -> {
                List<String> services = new ArrayList<>();
                input.path("services").forEach(s -> services.add(s.asText()));
                yield tools.getChangeEvents(incidentId, services, input.path("minutes").asInt(60), "");
            }
            case "compare_schema" -> tools.compareSchema(incidentId, input.path("serviceId").asText(), "");
            case "search_incident_memory" -> tools.searchIncidentMemory(incidentId,
                    input.path("symptoms").asText(), "");
            default -> throw new IllegalArgumentException("Unknown tool: " + name);
        };
    }

    private TriageReport buildReport(Incident incident, ScenarioDef scenario, ObjectNode reportNode, Instant start) {
        if (reportNode == null) {
            return new TriageReport(incident.getId(), "Inconclusive after " + MAX_TOOL_CALLS + " tool calls",
                    0, "The investigation exhausted its tool-call budget without a fully-evidenced causal chain.",
                    List.of(), List.of(), "unknown", "", List.of(),
                    List.of("See evidence ledger for services checked"),
                    Duration.between(start, Instant.now()).toMillis(), incident.getToolCallCount(),
                    redaction.count(incident.getId()), true);
        }
        List<TriageReport.CausalLink> chain = new ArrayList<>();
        for (JsonNode link : reportNode.path("causal_chain")) {
            long id = link.path("ledger_id").asLong();
            if (ledger.exists(incident.getId(), id)) {
                chain.add(new TriageReport.CausalLink(link.path("claim").asText(), id));
            }
        }
        List<String> remediation = new ArrayList<>();
        reportNode.path("remediation").forEach(r -> remediation.add(r.asText()));
        List<String> ruledOut = new ArrayList<>();
        reportNode.path("ruled_out").forEach(r -> ruledOut.add(r.asText()));
        String rootCauseService = reportNode.path("root_cause_service").asText("");
        var owner = topology.service(rootCauseService).orElse(null);
        return new TriageReport(
                incident.getId(),
                reportNode.path("root_cause").asText(),
                reportNode.path("confidence").asInt(50),
                reportNode.path("summary").asText(""),
                chain,
                topology.blastRadius(rootCauseService.isEmpty()
                        ? (scenario != null ? scenario.report().rootCauseService() : "") : rootCauseService),
                owner == null ? "unknown" : owner.team(),
                owner == null ? "" : owner.slack(),
                remediation,
                ruledOut,
                Duration.between(start, Instant.now()).toMillis(),
                incident.getToolCallCount(),
                redaction.count(incident.getId()),
                false);
    }

    private JsonNode callAnthropic(ArrayNode messages) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", 2000);
        body.put("system", SYSTEM_PROMPT);
        body.set("messages", messages);
        body.set("tools", toolDefinitions());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.anthropic.com/v1/messages"))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(90))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Anthropic API error " + response.statusCode() + ": "
                    + response.body());
        }
        return json.readTree(response.body());
    }

    private ArrayNode toolDefinitions() {
        ArrayNode defs = json.createArrayNode();
        defs.add(tool("get_topology", "Get the service dependency subgraph for a business flow (or the whole estate).",
                Map.of("flowId", "string?")));
        defs.add(tool("query_logs", "Recent scrubbed log lines for a service.",
                Map.of("serviceId", "string", "minutes", "integer")));
        defs.add(tool("query_metrics", "Error rate / throughput / latency summary for a service.",
                Map.of("serviceId", "string")));
        defs.add(tool("get_change_events", "Recent deployments and config changes for a set of services.",
                Map.of("services", "array", "minutes", "integer")));
        defs.add(tool("compare_schema", "Pre/post-deploy schema diff for a service's API responses.",
                Map.of("serviceId", "string")));
        defs.add(tool("search_incident_memory", "Search past incidents by symptom text (top-k similar).",
                Map.of("symptoms", "string")));
        defs.add(tool("set_node_status", "Update the investigation board: mark a service PROBING, CLEARED, SUSPECT or ROOT_CAUSE.",
                Map.of("service", "string", "status", "string")));
        defs.add(tool("update_hypotheses", "Publish the current ranked hypotheses (probabilities 0-100 summing to ~100).",
                Map.of("hypotheses", "array")));
        defs.add(tool("submit_report", "Submit the final triage report and end the investigation.",
                Map.of("root_cause", "string", "root_cause_service", "string", "confidence", "integer",
                        "summary", "string", "causal_chain", "array", "remediation", "array",
                        "ruled_out", "array")));
        return defs;
    }

    private ObjectNode tool(String name, String description, Map<String, String> props) {
        ObjectNode def = json.createObjectNode();
        def.put("name", name);
        def.put("description", description);
        ObjectNode schema = def.putObject("input_schema");
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        props.forEach((prop, type) -> {
            ObjectNode p = properties.putObject(prop);
            String t = type.replace("?", "");
            if (t.equals("array")) {
                p.put("type", "array");
                p.putObject("items").put("type", prop.equals("hypotheses") || prop.equals("causal_chain")
                        ? "object" : "string");
            } else {
                p.put("type", t);
            }
        });
        return def;
    }

    private static final String SYSTEM_PROMPT = """
            You are Triage Copilot, a senior SRE agent diagnosing production incidents in a bank's \
            regulatory-reporting estate. Follow this protocol strictly:
            1. SCOPE: map the alert to a business flow and call get_topology for its subgraph. \
            Mark each service in it with set_node_status status=PROBING as you inspect it.
            2. HYPOTHESIZE: call get_change_events and search_incident_memory, then publish 3-4 ranked \
            hypotheses via update_hypotheses. Weight recent change events near symptom onset heavily.
            3. INVESTIGATE: loop — pick the cheapest decisive check, call one tool, update node statuses \
            (CLEARED when exonerated, SUSPECT when implicated) and republish hypotheses as evidence shifts them. \
            Walk upstream/downstream along the topology following the evidence.
            4. VERIFY: before concluding, construct a causal chain in which EVERY link cites a ledger id \
            (each tool result is prefixed [ledger #N]). If a link lacks evidence, keep investigating. \
            You have a budget of 12 investigation tool calls; if exhausted, submit an inconclusive report \
            listing what you ruled out.
            5. REPORT: mark the culprit set_node_status status=ROOT_CAUSE, then call submit_report with \
            root_cause, root_cause_service (its service id), confidence (0-100), summary, causal_chain \
            [{claim, ledger_id}], remediation (drafts only, awaiting human approval), ruled_out.
            Keep any text you emit between tool calls to ONE short line — it is streamed to the UI \
            as your visible reasoning. Never invent log lines or metrics; only cite what tools returned.""";
}
