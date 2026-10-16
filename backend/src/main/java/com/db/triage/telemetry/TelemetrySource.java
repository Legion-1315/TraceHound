package com.db.triage.telemetry;

import com.db.triage.model.ScenarioDef;
import com.db.triage.model.Telemetry;

import java.util.List;
import java.util.Optional;

/**
 * Where the agent's tools read telemetry from: the in-process simulation (scripted demo) or the
 * live estate's own /ops endpoints. The agent code is identical either way.
 */
public interface TelemetrySource {

    /** "simulated" or "live". */
    String kind();

    /** Recent log lines for a service, newest first. */
    List<Telemetry.LogLine> queryLogs(String serviceId, int minutes);

    Telemetry.MetricsSummary queryMetrics(String serviceId);

    /** Deployments / config changes / maintenance across the given services (all when empty), newest first. */
    List<Telemetry.ChangeEvent> changeEvents(List<String> serviceIds, int minutes);

    /** Difference between the service's API response shape before and after its latest deployment. */
    Optional<ScenarioDef.SchemaDiff> schemaDiff(String serviceId);

    /** Raised when a service cannot be reached; the tools record this as evidence rather than crashing. */
    class ServiceUnreachableException extends RuntimeException {
        public ServiceUnreachableException(String serviceId, Throwable cause) {
            super(serviceId + " unreachable: " + rootMessage(cause), cause);
        }

        private static String rootMessage(Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            return root.getClass().getSimpleName() + (root.getMessage() == null ? "" : " " + root.getMessage());
        }
    }
}
