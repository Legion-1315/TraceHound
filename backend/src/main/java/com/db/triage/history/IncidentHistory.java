package com.db.triage.history;

import java.time.Instant;

public record IncidentHistory(
        String incidentId,
        Instant occurredAt,
        String scenarioId,
        String category,
        String rootCause,
        int confidence,
        int toolCalls
) {
}