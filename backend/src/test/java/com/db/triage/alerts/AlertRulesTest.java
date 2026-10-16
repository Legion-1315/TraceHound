package com.db.triage.alerts;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertRulesTest {

    @Test
    void healthyServiceRaisesNothing() {
        assertTrue(AlertRules.evaluate(new AlertRules.Inputs("svc", 600, 0.002, 118, 120, 40)).isEmpty());
    }

    @Test
    void errorRateLowAndHighVolumeAndLatency() {
        List<AlertRules.Firing> f = AlertRules.evaluate(new AlertRules.Inputs("regulatory-gateway", 600, 0.34, 120, 120, 40));
        assertEquals(1, f.size());
        assertEquals("ALERT [SEV2] regulatory-gateway: error rate 34.0% over last 1m (threshold 5%). Rule TH-ERR-001.",
                f.get(0).alertText());

        assertEquals("TH-VOL-001", AlertRules.evaluate(new AlertRules.Inputs("s", 600, 0, 10, 120, 40)).get(0).ruleId());
        assertEquals("TH-VOL-002", AlertRules.evaluate(new AlertRules.Inputs("s", 600, 0, 240, 120, 40)).get(0).ruleId());
        assertEquals("TH-LAT-001", AlertRules.evaluate(new AlertRules.Inputs("s", 600, 0, 120, 120, 1300)).get(0).ruleId());
    }

    @Test
    void volumeRulesWaitForWarmUp() {
        assertTrue(AlertRules.evaluate(new AlertRules.Inputs("s", 30, 0, 10, 120, 40)).isEmpty());
    }

    @Test
    void monitorKeepsIdAndFirstSeenWhileFiringAndClearsWhenResolved() {
        AlertMonitor monitor = new AlertMonitor(null, null, null);
        Instant t0 = Instant.parse("2026-10-16T10:00:00Z");
        AlertRules.Firing f = new AlertRules.Firing("s", "TH-ERR-001", "SEV2", "error rate 10.0%");
        monitor.update(List.of(f), t0);
        monitor.update(List.of(f), t0.plusSeconds(10));
        assertEquals(1, monitor.activeAlerts().size());
        assertEquals(t0, monitor.activeAlerts().get(0).firstSeen());
        assertEquals("AL-1", monitor.activeAlerts().get(0).id());
        monitor.update(List.of(), t0.plusSeconds(20));
        assertTrue(monitor.activeAlerts().isEmpty());
    }
}
