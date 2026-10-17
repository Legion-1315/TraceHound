package com.db.triage.llm;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spending guard-rails for a publicly reachable demo running on the owner's API key:
 * per-visitor hourly limit, daily investigation cap, daily estimated-cost cap, and a cap on
 * concurrent investigations. Counters are in memory and reset at midnight UTC.
 */
@Service
public class LlmBudget {

    public static class BudgetExceededException extends RuntimeException {
        public BudgetExceededException(String message) {
            super(message);
        }
    }

    /** Releases the concurrency slot when the investigation ends. */
    public final class Permit implements AutoCloseable {
        private final AtomicBoolean released = new AtomicBoolean();

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                concurrent.release();
            }
        }
    }

    private final int perClientPerHour;
    private final int perDay;
    private final double dailyCostUsd;
    private final int maxConcurrent;
    private final double inputPerMTok;
    private final double outputPerMTok;
    private final Semaphore concurrent;
    private final Clock clock;
    private final Map<String, Deque<Instant>> byClient = new HashMap<>();
    private LocalDate day;
    private int investigationsToday;
    private double costToday;

    @Autowired
    public LlmBudget(@Value("${triage.llm.max-investigations-per-client-per-hour:5}") int perClientPerHour,
                     @Value("${triage.llm.max-investigations-per-day:40}") int perDay,
                     @Value("${triage.llm.max-daily-cost-usd:5.0}") double dailyCostUsd,
                     @Value("${triage.llm.max-concurrent:2}") int maxConcurrent,
                     @Value("${triage.llm.price-input-per-mtok:2.0}") double inputPerMTok,
                     @Value("${triage.llm.price-output-per-mtok:10.0}") double outputPerMTok) {
        this(perClientPerHour, perDay, dailyCostUsd, maxConcurrent, inputPerMTok, outputPerMTok, Clock.systemUTC());
    }

    LlmBudget(int perClientPerHour, int perDay, double dailyCostUsd, int maxConcurrent, double inputPerMTok,
              double outputPerMTok, Clock clock) {
        this.perClientPerHour = perClientPerHour;
        this.perDay = perDay;
        this.dailyCostUsd = dailyCostUsd;
        this.maxConcurrent = maxConcurrent;
        this.inputPerMTok = inputPerMTok;
        this.outputPerMTok = outputPerMTok;
        this.concurrent = new Semaphore(maxConcurrent);
        this.clock = clock;
        this.day = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    /** Admit a new investigation for this client, or explain why not. */
    public synchronized Permit admit(String clientKey) {
        rollDay();
        if (costToday >= dailyCostUsd) {
            throw new BudgetExceededException(String.format(
                    "Daily LLM spend cap reached ($%.2f of $%.2f). Live investigations resume at 00:00 UTC.",
                    costToday, dailyCostUsd));
        }
        if (investigationsToday >= perDay) {
            throw new BudgetExceededException("Daily limit of " + perDay
                    + " live investigations reached. Resumes at 00:00 UTC.");
        }
        Instant now = clock.instant();
        Deque<Instant> recent = byClient.computeIfAbsent(clientKey, k -> new ArrayDeque<>());
        while (!recent.isEmpty() && recent.peekFirst().isBefore(now.minus(Duration.ofHours(1)))) {
            recent.pollFirst();
        }
        if (recent.size() >= perClientPerHour) {
            long waitMin = Duration.between(now, recent.peekFirst().plus(Duration.ofHours(1))).toMinutes() + 1;
            throw new BudgetExceededException("You have used your " + perClientPerHour
                    + " live investigations for this hour. Try again in about " + waitMin + " minutes.");
        }
        if (!concurrent.tryAcquire()) {
            throw new BudgetExceededException(maxConcurrent
                    + " investigations are already running. Please try again in a minute.");
        }
        recent.addLast(now);
        investigationsToday++;
        return new Permit();
    }

    /** Estimated cost of one API response's usage. Cache writes bill at 1.25x input, cache reads at 0.1x. */
    public double cost(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {
        return (inputTokens * inputPerMTok
                + cacheWriteTokens * inputPerMTok * 1.25
                + cacheReadTokens * inputPerMTok * 0.1
                + outputTokens * outputPerMTok) / 1_000_000.0;
    }

    public synchronized void recordCost(double usd) {
        rollDay();
        costToday += usd;
    }

    public synchronized boolean dailyCostExceeded() {
        rollDay();
        return costToday >= dailyCostUsd;
    }

    public synchronized Map<String, Object> status() {
        rollDay();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("investigationsToday", investigationsToday);
        out.put("maxInvestigationsPerDay", perDay);
        out.put("estimatedCostTodayUsd", Math.round(costToday * 10000.0) / 10000.0);
        out.put("maxDailyCostUsd", dailyCostUsd);
        out.put("maxPerClientPerHour", perClientPerHour);
        out.put("maxConcurrent", maxConcurrent);
        out.put("running", maxConcurrent - concurrent.availablePermits());
        return out;
    }

    private void rollDay() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (!today.equals(day)) {
            day = today;
            investigationsToday = 0;
            costToday = 0;
            byClient.clear();
        }
    }
}
