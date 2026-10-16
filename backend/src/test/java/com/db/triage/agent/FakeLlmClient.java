package com.db.triage.agent;

import com.db.triage.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stands in for the Anthropic API in tests. A script function receives the request and the turn
 * number and returns the assistant content blocks, so tests exercise the real agent loop
 * (tool dispatch, ledger, budget, report building) without the network.
 */
public class FakeLlmClient implements LlmClient {

    public record Turn(int number, ObjectNode request, List<Long> ledgerIdsSoFar) {
    }

    static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern LEDGER = Pattern.compile("\\[ledger #(\\d+)]");

    private volatile Function<Turn, ArrayNode> script = t -> JSON.createArrayNode();
    private volatile LlmException failure;
    public final List<ObjectNode> requests = new ArrayList<>();

    public void script(Function<Turn, ArrayNode> script) {
        this.script = script;
        this.failure = null;
        synchronized (requests) {
            requests.clear();
        }
    }

    public void failWith(LlmException e) {
        this.failure = e;
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public String model() {
        return "fake-model";
    }

    @Override
    public JsonNode createMessage(ObjectNode body) throws LlmException {
        if (failure != null) {
            throw failure;
        }
        int turn;
        synchronized (requests) {
            requests.add(body.deepCopy());
            turn = requests.size();
        }
        List<Long> ids = new ArrayList<>();
        Matcher m = LEDGER.matcher(body.path("messages").toString());
        while (m.find()) {
            ids.add(Long.parseLong(m.group(1)));
        }
        ObjectNode response = JSON.createObjectNode();
        response.set("content", script.apply(new Turn(turn, body, ids)));
        ObjectNode usage = response.putObject("usage");
        usage.put("input_tokens", 1000);
        usage.put("output_tokens", 100);
        usage.put("cache_read_input_tokens", 500);
        usage.put("cache_creation_input_tokens", 0);
        return response;
    }

    // --- helpers to build assistant content -------------------------------------------------------

    public static ArrayNode blocks() {
        return JSON.createArrayNode();
    }

    public static ArrayNode text(ArrayNode blocks, String text) {
        ObjectNode b = blocks.addObject();
        b.put("type", "text");
        b.put("text", text);
        return blocks;
    }

    public static ObjectNode toolUse(ArrayNode blocks, String name) {
        ObjectNode b = blocks.addObject();
        b.put("type", "tool_use");
        b.put("id", "toolu_" + name + "_" + blocks.size());
        b.put("name", name);
        return b.putObject("input");
    }
}
