package com.db.triage.history;

import java.time.Instant;

/** One historical incident — seeded corpus or a real completed investigation. */
public record IncidentHistory(
        String incidentId,
        Instant occurredAt,
        String scenarioId,
        String category,
        String rootCauseService,
        String serviceName,
        String severity,
        boolean critical,
        long mttrSeconds,
        boolean aiResolved,
        int confidence,
        int toolCalls
) {
}
