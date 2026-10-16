package com.db.triage.telemetry;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Fault injection into the live estate. Used by the chaos panel and the eval harness only —
 * nothing here is reachable from the agent's tools.
 */
@Service
public class EstateChaosClient {

    private final LiveEstateClient estate;
    private final RestClient http;

    public EstateChaosClient(LiveEstateClient estate, RestClient.Builder builder) {
        this.estate = estate;
        this.http = builder.build();
    }

    public Map<String, Object> inject(String serviceId, String fault) {
        return post(serviceId, "/chaos/" + fault);
    }

    public Map<String, Object> reset(String serviceId) {
        return post(serviceId, "/chaos/reset");
    }

    public Map<String, Object> clearHistory(String serviceId) {
        return post(serviceId, "/ops/admin/clear-history");
    }

    public Map<String, Object> state(String serviceId) {
        return http.get().uri(estate.baseUrl(serviceId) + "/chaos").retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
    }

    private Map<String, Object> post(String serviceId, String path) {
        return http.post().uri(estate.baseUrl(serviceId) + path).retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
    }
}
