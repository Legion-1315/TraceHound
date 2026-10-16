package com.db.triage.alerts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Threshold rules that turn a service's live metrics into alerts, the way a monitoring system would.
 * Pure functions so they are easy to test.
 */
public final class AlertRules {

    public record Firing(String serviceId, String ruleId, String severity, String description) {
        public String key() {
            return serviceId + "|" + ruleId;
        }

        public String alertText() {
            return String.format(Locale.ROOT, "ALERT [%s] %s: %s. Rule %s.", severity, serviceId, description, ruleId);
        }
    }

    public record Inputs(String serviceId, long uptimeSeconds, double errorRate, double throughputPerMin,
                         double baselinePerMin, long p95Ms) {
    }

    static final double ERROR_RATE_THRESHOLD = 0.05;
    static final double LOW_THROUGHPUT_FACTOR = 0.5;
    static final double HIGH_THROUGHPUT_FACTOR = 1.6;
    static final long P95_THRESHOLD_MS = 800;
    /** Throughput needs a full minute of traffic after start-up before it means anything. */
    static final long WARM_UP_SECONDS = 90;

    private AlertRules() {
    }

    public static List<Firing> evaluate(Inputs m) {
        List<Firing> out = new ArrayList<>();
        if (m.throughputPerMin() >= 5 && m.errorRate() > ERROR_RATE_THRESHOLD) {
            out.add(new Firing(m.serviceId(), "TH-ERR-001", "SEV2", String.format(Locale.ROOT,
                    "error rate %.1f%% over last 1m (threshold %.0f%%)", m.errorRate() * 100,
                    ERROR_RATE_THRESHOLD * 100)));
        }
        if (m.uptimeSeconds() >= WARM_UP_SECONDS && m.baselinePerMin() > 0) {
            if (m.throughputPerMin() < m.baselinePerMin() * LOW_THROUGHPUT_FACTOR) {
                out.add(new Firing(m.serviceId(), "TH-VOL-001", "SEV2", String.format(Locale.ROOT,
                        "throughput %.0f/min vs normal %.0f/min (below 50%%)", m.throughputPerMin(),
                        m.baselinePerMin())));
            } else if (m.throughputPerMin() > m.baselinePerMin() * HIGH_THROUGHPUT_FACTOR) {
                out.add(new Firing(m.serviceId(), "TH-VOL-002", "SEV3", String.format(Locale.ROOT,
                        "throughput %.0f/min vs normal %.0f/min (anomalously high)", m.throughputPerMin(),
                        m.baselinePerMin())));
            }
        }
        if (m.p95Ms() > P95_THRESHOLD_MS) {
            out.add(new Firing(m.serviceId(), "TH-LAT-001", "SEV3", String.format(Locale.ROOT,
                    "p95 latency %dms over last 1m (threshold %dms)", m.p95Ms(), P95_THRESHOLD_MS)));
        }
        return out;
    }

    public static Firing unreachable(String serviceId, String reason) {
        return new Firing(serviceId, "TH-UP-001", "SEV1", "health check failing (" + reason + ")");
    }
}
