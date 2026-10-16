package com.db.estate.ops;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Read-only operational telemetry for this service — the only thing the triage agent's tools read.
 * (In a real estate these would be Splunk/ELK, Prometheus, the CMDB and a schema registry.)
 */
@RestController
@RequestMapping("/ops")
public class OpsController {

    private final ServiceIdentity identity;
    private final OpsMetrics metrics;
    private final ChangeLog changes;
    private final SchemaRegistry schema;

    public OpsController(ServiceIdentity identity, OpsMetrics metrics, ChangeLog changes, SchemaRegistry schema) {
        this.identity = identity;
        this.metrics = metrics;
        this.changes = changes;
        this.schema = schema;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("service", identity.serviceId(), "release", identity.release(),
                "status", "UP", "uptimeSeconds", identity.uptimeSeconds());
    }

    @GetMapping("/logs")
    public List<LogBuffer.LogRecord> logs(@RequestParam(defaultValue = "15") int minutes,
                                          @RequestParam(defaultValue = "TRACE") String level,
                                          @RequestParam(defaultValue = "2000") int limit) {
        Instant since = Instant.now().minus(Duration.ofMinutes(Math.max(1, Math.min(minutes, 120))));
        return LogBuffer.since(since, level, Math.max(1, Math.min(limit, 5000)));
    }

    @GetMapping("/metrics")
    public OpsMetrics.Snapshot metrics() {
        return metrics.snapshot(identity.serviceId(), identity.release(), identity.uptimeSeconds());
    }

    @GetMapping("/changes")
    public List<ChangeLog.ChangeEvent> changes(@RequestParam(defaultValue = "60") int minutes) {
        return changes.since(Instant.now().minus(Duration.ofMinutes(Math.max(1, Math.min(minutes, 1440)))));
    }

    @GetMapping("/schema")
    public Map<String, Object> schema() {
        SchemaRegistry.SchemaView view = schema.view(identity.release());
        return Map.of("service", identity.serviceId(), "view", view);
    }

    /** Wipes logs, metrics and change history so an evaluation run starts from a clean slate. */
    @PostMapping("/admin/clear-history")
    public Map<String, String> clearHistory() {
        LogBuffer.clear();
        metrics.clear();
        changes.clear();
        return Map.of("service", identity.serviceId(), "cleared", "logs,metrics,changes");
    }
}
