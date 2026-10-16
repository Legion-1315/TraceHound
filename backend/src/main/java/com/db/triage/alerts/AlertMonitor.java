package com.db.triage.alerts;

import com.db.triage.model.Topology;
import com.db.triage.telemetry.LiveEstateClient;
import com.db.triage.telemetry.TelemetryRouter;
import com.db.triage.topology.TopologyService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Live estate only: polls every service's metrics every 10 seconds and keeps the set of firing alerts.
 * The UI lists them; an engineer clicks one to start an investigation. Nothing here talks to the agent.
 */
@Service
public class AlertMonitor {

    public record Alert(String id, String serviceId, String ruleId, String severity, String text,
                        Instant firstSeen, Instant lastSeen) {
    }

    private final TelemetryRouter telemetry;
    private final LiveEstateClient estate;
    private final TopologyService topology;
    private final Map<String, Alert> active = new LinkedHashMap<>();
    private int seq = 1;

    public AlertMonitor(TelemetryRouter telemetry, LiveEstateClient estate, TopologyService topology) {
        this.telemetry = telemetry;
        this.estate = estate;
        this.topology = topology;
    }

    @Scheduled(initialDelay = 10_000, fixedDelay = 10_000)
    public void poll() {
        if (!telemetry.isLive()) {
            return;
        }
        List<AlertRules.Firing> firing = new ArrayList<>();
        for (Topology.ServiceDef s : topology.topology().services()) {
            try {
                LiveEstateClient.LiveMetrics m = estate.metrics(s.id());
                firing.addAll(AlertRules.evaluate(new AlertRules.Inputs(s.id(), m.uptimeSeconds(), m.errorRate(),
                        m.throughputPerMin(), m.baselineThroughputPerMin(), m.latencyP95Ms())));
            } catch (RuntimeException e) {
                firing.add(AlertRules.unreachable(s.id(), "no response from /ops/metrics"));
            }
        }
        update(firing, Instant.now());
    }

    synchronized void update(List<AlertRules.Firing> firing, Instant now) {
        Set<String> keys = new HashSet<>();
        for (AlertRules.Firing f : firing) {
            keys.add(f.key());
            Alert existing = active.get(f.key());
            if (existing == null) {
                active.put(f.key(), new Alert("AL-" + (seq++), f.serviceId(), f.ruleId(), f.severity(),
                        f.alertText(), now, now));
            } else {
                // keep the alert id and first-seen time; refresh the text with current values
                active.put(f.key(), new Alert(existing.id(), f.serviceId(), f.ruleId(), f.severity(), f.alertText(),
                        existing.firstSeen(), now));
            }
        }
        active.keySet().removeIf(k -> !keys.contains(k));
    }

    public synchronized List<Alert> activeAlerts() {
        List<Alert> out = new ArrayList<>(active.values());
        out.sort(Comparator.comparing(Alert::severity).thenComparing(Alert::firstSeen));
        return out;
    }
}
