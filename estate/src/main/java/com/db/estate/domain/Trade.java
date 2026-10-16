package com.db.estate.domain;

import java.time.Instant;

/** An executed trade as published by trade-capture. Account and client ids are (synthetic) PII. */
public record Trade(
        String tradeId,
        String correlationId,
        String isin,
        String venue,
        int quantity,
        double price,
        String account,
        String clientId,
        Instant executedAt) {
}
