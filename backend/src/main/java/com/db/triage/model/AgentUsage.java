package com.db.triage.model;

/** What an LLM investigation consumed. Cost is an estimate from the configured per-token prices. */
public record AgentUsage(
        String model,
        int apiCalls,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheWriteTokens,
        double estimatedCostUsd) {

    public static AgentUsage empty(String model) {
        return new AgentUsage(model, 0, 0, 0, 0, 0, 0);
    }

    public AgentUsage plus(long input, long output, long cacheRead, long cacheWrite, double cost) {
        return new AgentUsage(model, apiCalls + 1, inputTokens + input, outputTokens + output,
                cacheReadTokens + cacheRead, cacheWriteTokens + cacheWrite,
                Math.round((estimatedCostUsd + cost) * 10000.0) / 10000.0);
    }
}
