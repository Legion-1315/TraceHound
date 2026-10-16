package com.db.triage.telemetry;

import com.db.triage.model.ScenarioDef;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveEstateClientDiffTest {

    @Test
    void reportsRemovedAndAddedFields() {
        Instant now = Instant.parse("2026-10-16T10:15:00Z");
        var view = new LiveEstateClient.SchemaView("2.4.1", "2.4.0", now.minusSeconds(13 * 60), List.of(
                new LiveEstateClient.EndpointSchema("GET /v1/currencies/{isin}",
                        List.of("currencyCode", "isin"), List.of("ccy", "isin")),
                new LiveEstateClient.EndpointSchema("GET /v1/lei/{clientId}",
                        List.of("lei"), List.of("lei"))));
        Optional<ScenarioDef.SchemaDiff> diff = LiveEstateClient.diff("ref-data-service", view, now);
        assertTrue(diff.isPresent());
        assertEquals("2.4.1 (previous 2.4.0, deployed 13m ago)", diff.get().release());
        assertTrue(diff.get().changes().contains("GET /v1/currencies/{isin} response: field 'ccy' REMOVED"));
        assertTrue(diff.get().changes().contains("GET /v1/currencies/{isin} response: field 'currencyCode' ADDED"));
        assertTrue(diff.get().changes().contains("GET /v1/lei/{clientId}: unchanged"));
    }

    @Test
    void noPreviousReleaseOrNoChangeMeansNoDiff() {
        Instant now = Instant.now();
        assertTrue(LiveEstateClient.diff("x", new LiveEstateClient.SchemaView("1.0", null, null, List.of()), now).isEmpty());
        var unchanged = new LiveEstateClient.SchemaView("1.1", "1.0", now, List.of(
                new LiveEstateClient.EndpointSchema("GET /a", List.of("a"), List.of("a"))));
        assertTrue(LiveEstateClient.diff("x", unchanged, now).isEmpty());
    }
}
