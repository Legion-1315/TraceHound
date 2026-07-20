package com.db.triage.model;

import java.time.Instant;
import java.util.Map;

public record LedgerEntry(
        long id,
        Instant timestamp,
        String tool,
        Map<String, Object> params,
        String resultSummary,
        String agentInference) {
}
