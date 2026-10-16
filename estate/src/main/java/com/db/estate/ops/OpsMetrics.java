package com.db.estate.ops;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Per-second counters for this service's primary operation (requests served, trades processed…).
 * Everything the dashboards and the agent see is computed from what actually happened here.
 */
@Component
public class OpsMetrics {

    public record MetricPoint(Instant timestamp, double value) {
    }

    public record Snapshot(
            String serviceId,
            String release,
            long uptimeSeconds,
            double errorRate,
            double throughputPerMin,
            double baselineThroughputPerMin,
            long latencyP95Ms,
            List<MetricPoint> errorRateSeries,
            List<MetricPoint> throughputSeries) {
    }

    private static final int WINDOW_SECONDS = 3700;
    private static final int SAMPLES_PER_SECOND = 32;

    private final Clock clock;
    private final double baselinePerMin;
    private final long[] epochSecond = new long[WINDOW_SECONDS];
    private final long[] count = new long[WINDOW_SECONDS];
    private final long[] errors = new long[WINDOW_SECONDS];
    private final long[][] latencies = new long[WINDOW_SECONDS][SAMPLES_PER_SECOND];
    private final int[] latencyCount = new int[WINDOW_SECONDS];

    public OpsMetrics(@Value("${estate.baseline-per-min:120}") double baselinePerMin) {
        this(baselinePerMin, Clock.systemUTC());
    }

    OpsMetrics(double baselinePerMin, Clock clock) {
        this.baselinePerMin = baselinePerMin;
        this.clock = clock;
        Arrays.fill(epochSecond, -1);
    }

    public synchronized void record(boolean error, long latencyMs) {
        long sec = clock.instant().getEpochSecond();
        int i = slot(sec);
        if (count[i] < Long.MAX_VALUE) {
            count[i]++;
        }
        if (error) {
            errors[i]++;
        }
        if (latencyCount[i] < SAMPLES_PER_SECOND) {
            latencies[i][latencyCount[i]++] = latencyMs;
        } else {
            // reservoir-ish: overwrite a rotating sample so slow tails still show up
            latencies[i][(int) (count[i] % SAMPLES_PER_SECOND)] = latencyMs;
        }
    }

    public synchronized Snapshot snapshot(String serviceId, String release, long uptimeSeconds) {
        long now = clock.instant().getEpochSecond();
        long c = 0;
        long e = 0;
        List<Long> lat = new ArrayList<>();
        for (long s = now - 59; s <= now; s++) {
            int i = existing(s);
            if (i < 0) continue;
            c += count[i];
            e += errors[i];
            for (int k = 0; k < latencyCount[i]; k++) lat.add(latencies[i][k]);
        }
        List<MetricPoint> errSeries = new ArrayList<>();
        List<MetricPoint> tputSeries = new ArrayList<>();
        for (int m = 60; m >= 0; m--) {
            long end = now - m * 60L;
            long mc = 0;
            long me = 0;
            for (long s = end - 59; s <= end; s++) {
                int i = existing(s);
                if (i < 0) continue;
                mc += count[i];
                me += errors[i];
            }
            Instant t = Instant.ofEpochSecond(end);
            errSeries.add(new MetricPoint(t, mc == 0 ? 0 : round4((double) me / mc)));
            tputSeries.add(new MetricPoint(t, mc));
        }
        return new Snapshot(serviceId, release, uptimeSeconds,
                c == 0 ? 0 : round4((double) e / c), c, baselinePerMin, p95(lat), errSeries, tputSeries);
    }

    public synchronized void clear() {
        Arrays.fill(epochSecond, -1);
        Arrays.fill(count, 0);
        Arrays.fill(errors, 0);
        Arrays.fill(latencyCount, 0);
    }

    private int slot(long sec) {
        int i = (int) Math.floorMod(sec, (long) WINDOW_SECONDS);
        if (epochSecond[i] != sec) {
            epochSecond[i] = sec;
            count[i] = 0;
            errors[i] = 0;
            latencyCount[i] = 0;
        }
        return i;
    }

    private int existing(long sec) {
        int i = (int) Math.floorMod(sec, (long) WINDOW_SECONDS);
        return epochSecond[i] == sec ? i : -1;
    }

    static long p95(List<Long> values) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int idx = (int) Math.ceil(0.95 * sorted.size()) - 1;
        return sorted.get(Math.max(0, idx));
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
