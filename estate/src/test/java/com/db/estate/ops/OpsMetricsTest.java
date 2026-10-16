package com.db.estate.ops;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpsMetricsTest {

    /** A clock the test can move. */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-16T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void errorRateThroughputAndP95ComeFromTheLastMinute() {
        MutableClock clock = new MutableClock();
        OpsMetrics metrics = new OpsMetrics(120, clock);
        for (int i = 0; i < 100; i++) {
            metrics.record(i < 25, i < 95 ? 10 : 900);
            if (i % 10 == 9) {
                clock.now = clock.now.plusSeconds(1);
            }
        }
        OpsMetrics.Snapshot s = metrics.snapshot("svc", "1.0.0", 60);
        assertEquals(100, s.throughputPerMin());
        assertEquals(0.25, s.errorRate());
        assertEquals(900, s.latencyP95Ms());
        assertEquals(120, s.baselineThroughputPerMin());
        assertEquals(61, s.throughputSeries().size());
    }

    @Test
    void oldTrafficFallsOutOfTheCurrentWindow() {
        MutableClock clock = new MutableClock();
        OpsMetrics metrics = new OpsMetrics(120, clock);
        metrics.record(true, 5);
        clock.now = clock.now.plusSeconds(120);
        metrics.record(false, 5);
        OpsMetrics.Snapshot s = metrics.snapshot("svc", "1.0.0", 180);
        assertEquals(1, s.throughputPerMin());
        assertEquals(0.0, s.errorRate());
    }

    @Test
    void p95OfEmptyIsZero() {
        assertEquals(0, OpsMetrics.p95(List.of()));
    }
}
