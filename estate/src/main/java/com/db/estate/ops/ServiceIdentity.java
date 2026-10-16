package com.db.estate.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Who this process is, which release it runs, and the single place deployments are recorded. */
@Component
public class ServiceIdentity {

    private static final Logger log = LoggerFactory.getLogger("com.db.estate.deploy");

    private final String serviceId;
    private final ChangeLog changes;
    private final SchemaRegistry schema;
    private final Instant startedAt = Instant.now();
    private final String initialRelease;
    private volatile String release;

    public ServiceIdentity(@Value("${estate.role}") String serviceId,
                           @Value("${estate.release:1.0.0}") String release,
                           ChangeLog changes, SchemaRegistry schema) {
        this.serviceId = serviceId;
        this.release = release;
        this.initialRelease = release;
        this.changes = changes;
        this.schema = schema;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void announce() {
        changes.record(new ChangeLog.ChangeEvent(Instant.now(), serviceId, "DEPLOYMENT",
                "Release " + release + " started (estate start-up)", "ci-pipeline"));
        log.info("{} release {} started", serviceId, release);
    }

    /** A deployment: keep the old response shape for schema diffs, apply the change, record it. */
    public synchronized void deploy(String newRelease, String summary, String author, Runnable change) {
        String old = release;
        schema.snapshotPrevious(old);
        change.run();
        release = newRelease;
        changes.record(new ChangeLog.ChangeEvent(Instant.now(), serviceId, "DEPLOYMENT", summary, author));
        log.info("Deployment complete: {} {} -> {}", serviceId, old, newRelease);
    }

    public void recordChange(String type, String summary, String author) {
        changes.record(new ChangeLog.ChangeEvent(Instant.now(), serviceId, type, summary, author));
    }

    /** Next patch version, e.g. 2.4.0 -> 2.4.1. */
    public String nextPatch() {
        String[] parts = release.split("\\.");
        try {
            parts[parts.length - 1] = String.valueOf(Integer.parseInt(parts[parts.length - 1]) + 1);
            return String.join(".", parts);
        } catch (NumberFormatException e) {
            return release + ".1";
        }
    }

    public String serviceId() {
        return serviceId;
    }

    public String release() {
        return release;
    }

    public String initialRelease() {
        return initialRelease;
    }

    public long uptimeSeconds() {
        return Duration.between(startedAt, Instant.now()).toSeconds();
    }
}
