package com.db.triage.llm;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmBudgetTest {

    private static Clock at(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    @Test
    void perClientHourlyLimit() {
        LlmBudget b = new LlmBudget(2, 100, 100, 10, 2, 10, at("2026-10-16T10:00:00Z"));
        b.admit("a").close();
        b.admit("a").close();
        var e = assertThrows(LlmBudget.BudgetExceededException.class, () -> b.admit("a"));
        assertTrue(e.getMessage().contains("this hour"));
        b.admit("b").close(); // other visitors are unaffected
    }

    @Test
    void concurrencyAndDailyCaps() {
        LlmBudget b = new LlmBudget(100, 3, 100, 1, 2, 10, at("2026-10-16T10:00:00Z"));
        LlmBudget.Permit p = b.admit("a");
        assertThrows(LlmBudget.BudgetExceededException.class, () -> b.admit("b"));
        p.close();
        p.close(); // idempotent
        b.admit("b").close();
        b.admit("c").close();
        var e = assertThrows(LlmBudget.BudgetExceededException.class, () -> b.admit("d"));
        assertTrue(e.getMessage().contains("Daily limit"));
    }

    @Test
    void costCapStopsAdmissions() {
        LlmBudget b = new LlmBudget(100, 100, 0.01, 5, 2, 10, at("2026-10-16T10:00:00Z"));
        // 1M input tokens at $2/MTok = $2
        assertEquals(2.0, b.cost(1_000_000, 0, 0, 0), 1e-9);
        assertEquals(0.2, b.cost(0, 0, 1_000_000, 0), 1e-9);
        b.recordCost(0.02);
        assertTrue(b.dailyCostExceeded());
        assertThrows(LlmBudget.BudgetExceededException.class, () -> b.admit("a"));
    }
}
