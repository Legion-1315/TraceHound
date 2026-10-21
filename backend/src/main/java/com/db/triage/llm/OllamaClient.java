package com.db.triage.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Runs the agent on a local model served by Ollama (https://ollama.com): free, no API key, nothing
 * leaves the machine. The agent loop speaks the Anthropic Messages format, so this client translates
 * each request to Ollama's native {@code /api/chat} (which, unlike its OpenAI-compatible endpoint,
 * lets us raise the context window) and translates the reply back into Anthropic-style content blocks.
 */
public class OllamaClient implements LlmClient {

    private static final Pattern THINK = Pattern.compile("(?s)<think>.*?</think>");
    /**
     * Small local models narrate at length before every tool call (600-1,100 tokens a turn measured with
     * qwen3:4b), which at laptop speeds is minutes per turn and fills the context window. Asking for
     * brevity is the cheapest fix.
     */
    static final String BREVITY = "Keep your written reasoning short: at most two sentences before each "
            + "tool call. Do not restate the alert or the protocol.";

    private final String model;
    private final URI endpoint;
    private final int numCtx;
    private final boolean think;
    private final Duration timeout;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AtomicLong toolIds = new AtomicLong();

    public OllamaClient(String baseUrl, String model, int numCtx, boolean think, Duration timeout) {
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/api/chat");
        this.model = model;
        this.numCtx = numCtx;
        this.think = think;
        this.timeout = timeout;
    }

    /** No key needed; whether the server is up is only known when we call it. */
    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public boolean billed() {
        return false;
    }

    @Override
    public String provider() {
        return "ollama";
    }

    @Override
    public JsonNode createMessage(ObjectNode body) throws LlmException {
        ObjectNode request = toOllama(body);
        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(endpoint)
                            .header("content-type", "application/json")
                            .timeout(timeout)
                            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException e) {
            throw new LlmException(0, "Could not reach Ollama at " + endpoint.getHost() + ":" + endpoint.getPort()
                    + ". Is it running? Start the Ollama app (or `ollama serve`) and check OLLAMA_BASE_URL.");
        } catch (java.net.http.HttpTimeoutException e) {
            throw new LlmException(0, "Ollama did not answer within " + timeout.toSeconds() + " s. The model may be "
                    + "too large for this machine; try a smaller OLLAMA_MODEL or raise OLLAMA_TIMEOUT_SECONDS.");
        } catch (IOException e) {
            throw new LlmException(0, "Network error calling Ollama: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(0, "Interrupted");
        }
        if (response.statusCode() != 200) {
            String error = response.body();
            try {
                error = json.readTree(response.body()).path("error").asText(error);
            } catch (IOException ignored) {
                // non-JSON error body
            }
            if (response.statusCode() == 404 || error.contains("not found")) {
                throw new LlmException(404, "Ollama has no model '" + model + "'. Run: ollama pull " + model);
            }
            throw new LlmException(response.statusCode(), "Ollama error " + response.statusCode() + ": " + error);
        }
        try {
            return fromOllama(json.readTree(response.body()));
        } catch (IOException e) {
            throw new LlmException(0, "Unreadable reply from Ollama: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Anthropic Messages request -> Ollama /api/chat request

    ObjectNode toOllama(ObjectNode body) {
        ObjectNode out = json.createObjectNode();
        out.put("model", model);
        out.put("stream", false);
        out.put("think", think);
        ObjectNode options = out.putObject("options");
        options.put("num_ctx", numCtx);
        if (body.has("max_tokens")) {
            options.put("num_predict", body.get("max_tokens").asInt());
        }

        ArrayNode messages = out.putArray("messages");
        StringBuilder system = new StringBuilder();
        JsonNode sys = body.path("system");
        if (sys.isTextual()) {
            system.append(sys.asText());
        } else {
            for (JsonNode block : sys) {
                system.append(block.path("text").asText()).append('\n');
            }
        }
        system.append('\n').append(BREVITY);
        messages.addObject().put("role", "system").put("content", system.toString().trim());

        Map<String, String> toolNames = new HashMap<>();
        for (JsonNode msg : body.path("messages")) {
            String role = msg.path("role").asText();
            JsonNode content = msg.path("content");
            if (content.isTextual()) {
                messages.addObject().put("role", role).put("content", content.asText());
                continue;
            }
            if (role.equals("assistant")) {
                ObjectNode a = messages.addObject().put("role", "assistant");
                StringBuilder text = new StringBuilder();
                ArrayNode calls = null;
                for (JsonNode block : content) {
                    switch (block.path("type").asText()) {
                        case "text" -> text.append(block.path("text").asText());
                        case "tool_use" -> {
                            toolNames.put(block.path("id").asText(), block.path("name").asText());
                            if (calls == null) calls = a.putArray("tool_calls");
                            ObjectNode fn = calls.addObject().putObject("function");
                            fn.put("name", block.path("name").asText());
                            fn.set("arguments", block.path("input").isObject() ? block.get("input")
                                    : json.createObjectNode());
                        }
                        default -> {
                            // thinking and other block types have no Ollama equivalent
                        }
                    }
                }
                a.put("content", text.toString());
            } else {
                // A user turn: tool results become "tool" messages (right after the assistant's calls),
                // any text that follows them becomes a user message.
                StringBuilder text = new StringBuilder();
                for (JsonNode block : content) {
                    String type = block.path("type").asText();
                    if (type.equals("tool_result")) {
                        String result = block.path("content").isTextual() ? block.path("content").asText()
                                : joinText(block.path("content"));
                        if (block.path("is_error").asBoolean(false)) {
                            result = "ERROR: " + result;
                        }
                        ObjectNode t = messages.addObject().put("role", "tool").put("content", result);
                        String name = toolNames.get(block.path("tool_use_id").asText());
                        if (name != null) {
                            t.put("tool_name", name);
                        }
                    } else if (type.equals("text")) {
                        text.append(block.path("text").asText()).append('\n');
                    }
                }
                if (!text.isEmpty()) {
                    messages.addObject().put("role", "user").put("content", text.toString().trim());
                }
            }
        }

        ArrayNode tools = out.putArray("tools");
        for (JsonNode tool : body.path("tools")) {
            ObjectNode fn = tools.addObject().put("type", "function").putObject("function");
            fn.put("name", tool.path("name").asText());
            fn.put("description", tool.path("description").asText());
            ObjectNode schema = tool.path("input_schema").deepCopy();
            schema.remove("cache_control");
            fn.set("parameters", schema);
        }
        return out;
    }

    private static String joinText(JsonNode blocks) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode b : blocks) {
            sb.append(b.path("text").asText());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------------
    // Ollama /api/chat reply -> Anthropic Messages reply

    ObjectNode fromOllama(JsonNode reply) {
        ObjectNode out = json.createObjectNode();
        out.put("type", "message");
        out.put("role", "assistant");
        out.put("model", model);
        ArrayNode content = out.putArray("content");
        JsonNode message = reply.path("message");

        String text = THINK.matcher(message.path("content").asText("")).replaceAll("").trim();
        if (!text.isEmpty()) {
            content.addObject().put("type", "text").put("text", text);
        }
        boolean toolUse = false;
        for (JsonNode call : message.path("tool_calls")) {
            JsonNode fn = call.path("function");
            JsonNode args = fn.path("arguments");
            if (args.isTextual()) {
                try {
                    args = json.readTree(args.asText());
                } catch (IOException e) {
                    args = json.createObjectNode();
                }
            }
            ObjectNode block = content.addObject();
            block.put("type", "tool_use");
            block.put("id", "toolu_ollama_" + toolIds.incrementAndGet());
            block.put("name", fn.path("name").asText());
            block.set("input", args.isObject() ? args : json.createObjectNode());
            toolUse = true;
        }
        out.put("stop_reason", toolUse ? "tool_use"
                : "length".equals(reply.path("done_reason").asText()) ? "max_tokens" : "end_turn");
        ObjectNode usage = out.putObject("usage");
        usage.put("input_tokens", reply.path("prompt_eval_count").asLong());
        usage.put("output_tokens", reply.path("eval_count").asLong());
        return out;
    }
}
