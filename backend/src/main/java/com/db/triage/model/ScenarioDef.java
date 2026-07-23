package com.db.triage.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/** A fault scenario: telemetry injections, planted change events, and the scripted investigation. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScenarioDef(
        String id,
        String name,
        String description,
        String category,
        @JsonProperty("alert_text") String alertText,
        String flow,
        @JsonProperty("expected_root_cause") String expectedRootCause,
        @JsonProperty("change_events") List<PlantedChange> changeEvents,
        List<Injection> injections,
        @JsonProperty("schema_diff") SchemaDiff schemaDiff,
        List<ScriptStep> script,
        @JsonProperty("shortcut_script") List<ScriptStep> shortcutScript,
        Report report) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlantedChange(String service, @JsonProperty("minutes_ago") int minutesAgo, String type,
                                String summary, String author) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Injection(
            String service,
            @JsonProperty("from_minutes_ago") int fromMinutesAgo,
            Metric metric,
            List<InjectedLog> logs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Metric(
            @JsonProperty("error_rate") Double errorRate,
            @JsonProperty("throughput_factor") Double throughputFactor,
            @JsonProperty("latency_ms") Long latencyMs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InjectedLog(String level, @JsonProperty("every_s") int everySeconds, String message) {
    }

    /** Pre/post deploy schema diff surfaced by the compare_schema tool (scenario 1). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SchemaDiff(String service, String release, List<String> changes) {
    }

    /** One step of the scripted (deterministic) investigation. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScriptStep(
            String thought,
            String tool,
            Map<String, Object> params,
            String inference,
            @JsonProperty("ledger_key") String ledgerKey,
            @JsonProperty("node_status") Map<String, String> nodeStatus,
            List<Hypothesis> hypotheses) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hypothesis(String text, int probability) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Report(
            @JsonProperty("root_cause") String rootCause,
            int confidence,
            String summary,
            @JsonProperty("causal_chain") List<ChainLink> causalChain,
            List<String> remediation,
            @JsonProperty("ruled_out") List<String> ruledOut,
            @JsonProperty("root_cause_service") String rootCauseService) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChainLink(String claim, @JsonProperty("ledger_key") String ledgerKey) {
    }
}
