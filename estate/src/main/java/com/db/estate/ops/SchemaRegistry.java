package com.db.estate.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * The response shape of this service's public endpoints, taken from live sample responses.
 * On every deployment the pre-deploy shape is kept, so a schema diff compares what the
 * service returned before the release with what it returns now.
 */
@Component
public class SchemaRegistry {

    public record EndpointSchema(String endpoint, List<String> currentFields, List<String> previousFields) {
    }

    public record SchemaView(String release, String previousRelease, Instant deployedAt,
                             List<EndpointSchema> endpoints) {
    }

    private final ObjectMapper json;
    private final Map<String, Supplier<Object>> samplers = new LinkedHashMap<>();
    private volatile Map<String, List<String>> previous = Map.of();
    private volatile String previousRelease;
    private volatile Instant deployedAt;

    public SchemaRegistry(ObjectMapper json) {
        this.json = json;
    }

    public void register(String endpoint, Supplier<Object> sampler) {
        samplers.put(endpoint, sampler);
    }

    /** Called just before a deployment changes behaviour. */
    public synchronized void snapshotPrevious(String releaseBeingReplaced) {
        Map<String, List<String>> snap = new LinkedHashMap<>();
        samplers.forEach((endpoint, sampler) -> snap.put(endpoint, fields(sampler.get())));
        previous = snap;
        previousRelease = releaseBeingReplaced;
        deployedAt = Instant.now();
    }

    public SchemaView view(String currentRelease) {
        List<EndpointSchema> endpoints = new ArrayList<>();
        samplers.forEach((endpoint, sampler) -> endpoints.add(new EndpointSchema(endpoint,
                fields(sampler.get()), previousRelease == null ? null : previous.get(endpoint))));
        return new SchemaView(currentRelease, previousRelease, deployedAt, endpoints);
    }

    List<String> fields(Object sample) {
        TreeSet<String> out = new TreeSet<>();
        collect("", json.valueToTree(sample), out);
        return List.copyOf(out);
    }

    private static void collect(String prefix, JsonNode node, TreeSet<String> out) {
        if (node == null || !node.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            out.add(path);
            collect(path, e.getValue(), out);
        }
    }
}
