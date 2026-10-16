package com.db.triage.telemetry;

import com.db.triage.model.ScenarioDef;
import com.db.triage.model.Telemetry;
import com.db.triage.sim.SimulationEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * The telemetry source the agent's tools use: TRIAGE_ESTATE=simulated (default, in-process demo)
 * or TRIAGE_ESTATE=live (the real estate services' /ops endpoints).
 */
@Service
@Primary
public class TelemetryRouter implements TelemetrySource {

    private final TelemetrySource delegate;

    public TelemetryRouter(@Value("${triage.estate}") String estate, SimulationEngine simulation,
                           LiveEstateClient live) {
        this.delegate = switch (estate.trim().toLowerCase()) {
            case "live" -> live;
            case "simulated", "sim", "" -> simulation;
            default -> throw new IllegalStateException(
                    "triage.estate must be 'simulated' or 'live', was: " + estate);
        };
    }

    public boolean isLive() {
        return "live".equals(delegate.kind());
    }

    @Override
    public String kind() {
        return delegate.kind();
    }

    @Override
    public List<Telemetry.LogLine> queryLogs(String serviceId, int minutes) {
        return delegate.queryLogs(serviceId, minutes);
    }

    @Override
    public Telemetry.MetricsSummary queryMetrics(String serviceId) {
        return delegate.queryMetrics(serviceId);
    }

    @Override
    public List<Telemetry.ChangeEvent> changeEvents(List<String> serviceIds, int minutes) {
        return delegate.changeEvents(serviceIds, minutes);
    }

    @Override
    public Optional<ScenarioDef.SchemaDiff> schemaDiff(String serviceId) {
        return delegate.schemaDiff(serviceId);
    }
}
