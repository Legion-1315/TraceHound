package com.db.triage.model;

import java.time.Instant;
import java.util.List;

public final class Telemetry {

    public record LogLine(Instant timestamp, String level, String correlationId, String message) {
    }

    public record MetricsSummary(
            String serviceId,
            double errorRate,
            double throughputPerMin,
            double baselineThroughputPerMin,
            long latencyP95Ms,
            List<MetricPoint> errorRateSeries,
            List<MetricPoint> throughputSeries) {
    }

    public record MetricPoint(Instant timestamp, double value) {
    }

    public record ChangeEvent(Instant timestamp, String serviceId, String type, String summary, String author) {
    }

    private Telemetry() {
    }
}
