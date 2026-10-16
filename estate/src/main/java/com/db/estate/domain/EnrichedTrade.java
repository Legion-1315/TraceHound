package com.db.estate.domain;

/** A trade after enrichment with reference data; {@code currency} is mandatory for MiFID reporting. */
public record EnrichedTrade(Trade trade, String currency, String lei) {
}
