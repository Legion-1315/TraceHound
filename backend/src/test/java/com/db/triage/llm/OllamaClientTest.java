package com.db.triage.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Anthropic-format conversation must survive the round trip through Ollama's /api/chat format. */
class OllamaClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static OllamaClient client(String url) {
        return new OllamaClient(url, "qwen3:8b", 16384, false, Duration.ofSeconds(10));
    }

    /** An Anthropic Messages request as the agent builds it: system, tools, one tool round trip. */
    private static ObjectNode anthropicRequest() throws Exception {
        return (ObjectNode) JSON.readTree("""
                {"max_tokens": 4096,
                 "system": [{"type":"text","text":"You are an SRE.","cache_control":{"type":"ephemeral"}}],
                 "tools": [{"name":"query_metrics","description":"Metrics of one service",
                            "input_schema":{"type":"object","properties":{"serviceId":{"type":"string"}},
                                            "required":["serviceId"]}}],
                 "messages": [
                   {"role":"user","content":[{"type":"text","text":"Alert: gateway errors"}]},
                   {"role":"assistant","content":[
                      {"type":"text","text":"Checking the gateway."},
                      {"type":"tool_use","id":"toolu_1","name":"query_metrics","input":{"serviceId":"regulatory-gateway"}}]},
                   {"role":"user","content":[
                      {"type":"tool_result","tool_use_id":"toolu_1","content":"[ledger #3] error rate 40%",
                       "cache_control":{"type":"ephemeral"}},
                      {"type":"text","text":"Call submit_report now."}]}
                 ]}
                """);
    }

    @Test
    void translatesTheRequestToOllamaChat() throws Exception {
        ObjectNode out = client("http://localhost:1").toOllama(anthropicRequest());

        assertEquals("qwen3:8b", out.path("model").asText());
        assertFalse(out.path("stream").asBoolean());
        assertEquals(16384, out.path("options").path("num_ctx").asInt());
        assertEquals(4096, out.path("options").path("num_predict").asInt());

        JsonNode msgs = out.path("messages");
        assertEquals(5, msgs.size());
        assertEquals("system", msgs.get(0).path("role").asText());
        assertTrue(msgs.get(0).path("content").asText().startsWith("You are an SRE."));
        assertTrue(msgs.get(0).path("content").asText().endsWith(OllamaClient.BREVITY));
        assertEquals("user", msgs.get(1).path("role").asText());
        assertEquals("Alert: gateway errors", msgs.get(1).path("content").asText());

        JsonNode assistant = msgs.get(2);
        assertEquals("Checking the gateway.", assistant.path("content").asText());
        JsonNode call = assistant.path("tool_calls").get(0).path("function");
        assertEquals("query_metrics", call.path("name").asText());
        assertEquals("regulatory-gateway", call.path("arguments").path("serviceId").asText());

        // the tool result comes straight after the call, then the trailing instruction as a user message
        assertEquals("tool", msgs.get(3).path("role").asText());
        assertEquals("query_metrics", msgs.get(3).path("tool_name").asText());
        assertEquals("[ledger #3] error rate 40%", msgs.get(3).path("content").asText());
        assertEquals("user", msgs.get(4).path("role").asText());
        assertEquals("Call submit_report now.", msgs.get(4).path("content").asText());

        JsonNode fn = out.path("tools").get(0).path("function");
        assertEquals("query_metrics", fn.path("name").asText());
        assertEquals("serviceId", fn.path("parameters").path("required").get(0).asText());
    }

    @Test
    void translatesToolCallsAndUsageBack() throws Exception {
        JsonNode reply = JSON.readTree("""
                {"message":{"role":"assistant","content":"<think>hmm</think>Looking upstream.",
                  "tool_calls":[{"function":{"name":"query_logs","arguments":{"serviceId":"ref-data-service"}}},
                                {"function":{"name":"query_metrics","arguments":"{\\"serviceId\\":\\"enrichment-service\\"}"}}]},
                 "done":true,"done_reason":"stop","prompt_eval_count":1200,"eval_count":85}
                """);
        ObjectNode out = client("http://localhost:1").fromOllama(reply);

        assertEquals("tool_use", out.path("stop_reason").asText());
        JsonNode content = out.path("content");
        assertEquals("text", content.get(0).path("type").asText());
        assertEquals("Looking upstream.", content.get(0).path("text").asText());
        assertEquals("tool_use", content.get(1).path("type").asText());
        assertEquals("ref-data-service", content.get(1).path("input").path("serviceId").asText());
        // arguments sent as a JSON string are parsed too
        assertEquals("enrichment-service", content.get(2).path("input").path("serviceId").asText());
        assertFalse(content.get(1).path("id").asText().equals(content.get(2).path("id").asText()));
        assertEquals(1200, out.path("usage").path("input_tokens").asLong());
        assertEquals(85, out.path("usage").path("output_tokens").asLong());
    }

    @Test
    void plainAnswerEndsTheTurn() throws Exception {
        ObjectNode out = client("http://localhost:1").fromOllama(JSON.readTree(
                "{\"message\":{\"role\":\"assistant\",\"content\":\"Done.\"},\"done_reason\":\"stop\"}"));
        assertEquals("end_turn", out.path("stop_reason").asText());
        assertEquals(1, out.path("content").size());
    }

    @Test
    void callsTheServerAndReportsAMissingModelClearly() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/chat", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.set(body);
            boolean known = body.contains("\"qwen3:8b\"");
            byte[] reply = (known
                    ? "{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"done_reason\":\"stop\",\"prompt_eval_count\":5,\"eval_count\":1}"
                    : "{\"error\":\"model 'nope' not found\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(known ? 200 : 404, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://localhost:" + server.getAddress().getPort();
            JsonNode ok = client(url).createMessage(anthropicRequest());
            assertEquals("ok", ok.path("content").get(0).path("text").asText());
            assertTrue(seen.get().contains("\"num_ctx\":16384"));

            OllamaClient missing = new OllamaClient(url, "nope", 4096, false, Duration.ofSeconds(10));
            LlmClient.LlmException e = assertThrows(LlmClient.LlmException.class,
                    () -> missing.createMessage(anthropicRequest()));
            assertTrue(e.getMessage().contains("ollama pull nope"), e.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anUnreachableServerSaysToStartOllama() {
        // port 1 is never an Ollama server
        LlmClient.LlmException e = assertThrows(LlmClient.LlmException.class,
                () -> client("http://localhost:1").createMessage(anthropicRequest()));
        assertTrue(e.getMessage().contains("Is it running?"), e.getMessage());
    }

    @Test
    void localModelsAreNotBilled() {
        assertFalse(client("http://localhost:1").billed());
        assertEquals("ollama", client("http://localhost:1").provider());
    }
}
