package com.db.triage.incident;

import com.db.triage.model.TriageReport;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class Incident {

    public enum Status {RUNNING, COMPLETE, FAILED}

    private final String id;
    private final String alertText;
    private final String scenarioId;
    private final String mode;
    private final Instant startedAt = Instant.now();
    private final AtomicReference<Status> status = new AtomicReference<>(Status.RUNNING);
    private final AtomicReference<TriageReport> report = new AtomicReference<>();
    private final AtomicInteger toolCalls = new AtomicInteger();

    public Incident(String id, String alertText, String scenarioId, String mode) {
        this.id = id;
        this.alertText = alertText;
        this.scenarioId = scenarioId;
        this.mode = mode;
    }

    public String getId() {
        return id;
    }

    public String getAlertText() {
        return alertText;
    }

    public String getScenarioId() {
        return scenarioId;
    }

    public String getMode() {
        return mode;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Status getStatus() {
        return status.get();
    }

    public void setStatus(Status s) {
        status.set(s);
    }

    public TriageReport getReport() {
        return report.get();
    }

    public void setReport(TriageReport r) {
        report.set(r);
    }

    public int incrementToolCalls() {
        return toolCalls.incrementAndGet();
    }

    public int getToolCallCount() {
        return toolCalls.get();
    }
}
