package com.db.triage.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Topology(List<ServiceDef> services, List<FlowDef> flows) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServiceDef(
            String id,
            String name,
            String description,
            String team,
            String slack,
            List<String> upstream,
            List<String> downstream,
            @JsonProperty("business_flows") List<String> businessFlows,
            @JsonProperty("known_failure_modes") List<String> knownFailureModes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FlowDef(String id, String name, List<String> path, String criticality) {
    }
}
