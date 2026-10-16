package com.db.estate.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SchemaRegistryTest {

    @Test
    void keepsThePreDeploymentShapeForDiffing() {
        SchemaRegistry registry = new SchemaRegistry(new ObjectMapper());
        AtomicBoolean renamed = new AtomicBoolean();
        registry.register("GET /x", () -> renamed.get()
                ? Map.of("isin", "A", "currencyCode", "EUR")
                : Map.of("isin", "A", "ccy", "EUR"));

        SchemaRegistry.SchemaView before = registry.view("2.4.0");
        assertNull(before.previousRelease());
        assertEquals(List.of("ccy", "isin"), before.endpoints().get(0).currentFields());

        registry.snapshotPrevious("2.4.0");
        renamed.set(true);
        SchemaRegistry.SchemaView after = registry.view("2.4.1");
        assertEquals("2.4.0", after.previousRelease());
        assertEquals(List.of("ccy", "isin"), after.endpoints().get(0).previousFields());
        assertEquals(List.of("currencyCode", "isin"), after.endpoints().get(0).currentFields());
    }

    @Test
    void nestedFieldsUseDotPaths() {
        SchemaRegistry registry = new SchemaRegistry(new ObjectMapper());
        assertEquals(List.of("a", "a.b"), registry.fields(Map.of("a", Map.of("b", 1))));
    }
}
