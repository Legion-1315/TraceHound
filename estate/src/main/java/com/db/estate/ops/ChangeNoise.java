package com.db.estate.ops;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Real estates change constantly. Harmless config changes are recorded now and then so that
 * "something changed recently" is never, on its own, proof of a root cause.
 */
@Component
public class ChangeNoise {

    private static final List<String> HARMLESS = List.of(
            "Log level for com.db.estate set to INFO (was DEBUG)",
            "HTTP client connection pool max-idle 10 -> 12",
            "Feature flag 'ops-banner-v2' toggled on",
            "JVM metrics scrape interval 30s -> 15s",
            "Alert routing: on-call rota updated for this week",
            "Dashboard 'service overview' panel thresholds adjusted");

    private final ServiceIdentity identity;
    private final boolean enabled;

    public ChangeNoise(ServiceIdentity identity, @Value("${estate.change-noise.enabled:true}") boolean enabled) {
        this.identity = identity;
        this.enabled = enabled;
    }

    @Scheduled(initialDelayString = "${estate.change-noise.initial-delay-ms:240000}",
            fixedDelayString = "${estate.change-noise.interval-ms:300000}")
    public void maybeChangeSomething() {
        if (!enabled || ThreadLocalRandom.current().nextDouble() > 0.25) {
            return;
        }
        String summary = HARMLESS.get(ThreadLocalRandom.current().nextInt(HARMLESS.size()));
        identity.recordChange("CONFIG", summary, "ops-bot");
    }
}
