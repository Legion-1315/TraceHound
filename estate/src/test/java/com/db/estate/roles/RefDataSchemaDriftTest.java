package com.db.estate.roles;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The schema-drift fault really changes the response, and the schema endpoint shows the before/after. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "estate.change-noise.enabled=false")
@ActiveProfiles("ref-data-service")
@DirtiesContext
class RefDataSchemaDriftTest {

    @Autowired
    TestRestTemplate http;

    @Test
    @SuppressWarnings("unchecked")
    void schemaDriftRenamesTheCurrencyFieldAndIsVisibleInSchemaAndChanges() {
        Map<String, Object> before = http.getForObject("/v1/currencies/DE000BAY0017", Map.class);
        assertEquals("EUR", before.get("ccy"));

        http.postForObject("/chaos/schema-drift", null, Map.class);

        Map<String, Object> after = http.getForObject("/v1/currencies/DE000BAY0017", Map.class);
        assertFalse(after.containsKey("ccy"));
        assertEquals("EUR", after.get("currencyCode"));

        Map<String, Object> schema = http.getForObject("/ops/schema", Map.class);
        Map<String, Object> view = (Map<String, Object>) schema.get("view");
        assertEquals("2.4.1", view.get("release"));
        assertEquals("2.4.0", view.get("previousRelease"));
        Map<String, Object> currencies = ((List<Map<String, Object>>) view.get("endpoints")).get(0);
        assertTrue(((List<String>) currencies.get("previousFields")).contains("ccy"));
        assertTrue(((List<String>) currencies.get("currentFields")).contains("currencyCode"));

        List<Map<String, Object>> changes = http.getForObject("/ops/changes?minutes=5", List.class);
        assertTrue(changes.stream().anyMatch(c -> String.valueOf(c.get("summary")).contains("2.4.1")));

        http.postForObject("/chaos/reset", null, Map.class);
        Map<String, Object> restored = http.getForObject("/v1/currencies/DE000BAY0017", Map.class);
        assertEquals("EUR", restored.get("ccy"));
    }
}
