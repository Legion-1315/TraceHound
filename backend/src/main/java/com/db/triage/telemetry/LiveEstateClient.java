package com.db.triage.telemetry;

import com.db.triage.model.ScenarioDef;
import com.db.triage.model.Telemetry;
import com.db.triage.topology.TopologyService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads telemetry from the running estate services' {@code /ops/*} endpoints. Read-only by design:
 * this class never touches {@code /chaos} — fault injection lives in {@link EstateChaosClient}.
 */
@Service
public class LiveEstateClient implements TelemetrySource {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LogRecord(Instant timestamp, String level, String correlationId, String logger, String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Point(Instant timestamp, double value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LiveMetrics(String serviceId, String release, long uptimeSeconds, double errorRate,
                              double throughputPerMin, double baselineThroughputPerMin, long latencyP95Ms,
                              List<Point> errorRateSeries, List<Point> throughputSeries) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Change(Instant timestamp, String serviceId, String type, String summary, String author) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EndpointSchema(String endpoint, List<String> currentFields, List<String> previousFields) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SchemaView(String release, String previousRelease, Instant deployedAt, List<EndpointSchema> endpoints) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SchemaResponse(String service, SchemaView view) {
    }

    private final TopologyService topology;
    private final Environment env;
    private final RestClient http;

    public LiveEstateClient(TopologyService topology, Environment env, RestClient.Builder builder) {
        this.topology = topology;
        this.env = env;
        HttpClient jdk = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = builder.clone().requestFactory(factory).build();
    }

    @Override
    public String kind() {
        return "live";
    }

    public String baseUrl(String serviceId) {
        String url = env.getProperty("triage.estate.urls." + serviceId);
        if (url == null || topology.service(serviceId).isEmpty()) {
            throw new IllegalArgumentException("Unknown service '" + serviceId + "'. Known services: "
                    + topology.topology().services().stream().map(s -> s.id()).toList());
        }
        return url;
    }

    @Override
    public List<Telemetry.LogLine> queryLogs(String serviceId, int minutes) {
        List<LogRecord> records = get(serviceId, "/ops/logs?minutes=" + minutes + "&limit=3000",
                new ParameterizedTypeReference<>() {
                });
        List<Telemetry.LogLine> lines = new ArrayList<>();
        if (records != null) {
            for (LogRecord r : records) {
                lines.add(new Telemetry.LogLine(r.timestamp(), r.level(), r.correlationId(), r.message()));
            }
        }
        return lines;
    }

    @Override
    public Telemetry.MetricsSummary queryMetrics(String serviceId) {
        LiveMetrics m = metrics(serviceId);
        return new Telemetry.MetricsSummary(m.serviceId(), m.errorRate(), m.throughputPerMin(),
                m.baselineThroughputPerMin(), m.latencyP95Ms(), points(m.errorRateSeries()),
                points(m.throughputSeries()));
    }

    /** Full live metrics, including release and uptime (used by the alert monitor). */
    public LiveMetrics metrics(String serviceId) {
        return get(serviceId, "/ops/metrics", new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public List<Telemetry.ChangeEvent> changeEvents(List<String> serviceIds, int minutes) {
        List<String> targets = serviceIds == null || serviceIds.isEmpty()
                ? topology.topology().services().stream().map(s -> s.id()).toList()
                : serviceIds;
        List<Telemetry.ChangeEvent> out = new ArrayList<>();
        for (String id : targets) {
            List<Change> changes = get(id, "/ops/changes?minutes=" + minutes, new ParameterizedTypeReference<>() {
            });
            if (changes != null) {
                for (Change c : changes) {
                    out.add(new Telemetry.ChangeEvent(c.timestamp(), c.serviceId(), c.type(), c.summary(), c.author()));
                }
            }
        }
        out.sort(Comparator.comparing(Telemetry.ChangeEvent::timestamp).reversed());
        return out;
    }

    @Override
    public Optional<ScenarioDef.SchemaDiff> schemaDiff(String serviceId) {
        SchemaResponse response = get(serviceId, "/ops/schema", new ParameterizedTypeReference<>() {
        });
        return response == null ? Optional.empty() : diff(serviceId, response.view(), Instant.now());
    }

    /** Pure: turn a before/after schema view into human-readable changes (empty when nothing changed). */
    static Optional<ScenarioDef.SchemaDiff> diff(String serviceId, SchemaView view, Instant now) {
        if (view == null || view.previousRelease() == null || view.endpoints() == null) {
            return Optional.empty();
        }
        List<String> changes = new ArrayList<>();
        for (EndpointSchema e : view.endpoints()) {
            if (e.previousFields() == null) continue;
            Set<String> before = new LinkedHashSet<>(e.previousFields());
            Set<String> after = new LinkedHashSet<>(e.currentFields() == null ? List.of() : e.currentFields());
            for (String f : before) {
                if (!after.contains(f)) changes.add(e.endpoint() + " response: field '" + f + "' REMOVED");
            }
            for (String f : after) {
                if (!before.contains(f)) changes.add(e.endpoint() + " response: field '" + f + "' ADDED");
            }
            if (before.equals(after)) {
                changes.add(e.endpoint() + ": unchanged");
            }
        }
        if (changes.stream().allMatch(c -> c.endsWith(": unchanged"))) {
            return Optional.empty();
        }
        String when = view.deployedAt() == null ? ""
                : ", deployed " + Duration.between(view.deployedAt(), now).toMinutes() + "m ago";
        return Optional.of(new ScenarioDef.SchemaDiff(serviceId,
                view.release() + " (previous " + view.previousRelease() + when + ")", changes));
    }

    /** Health probe used by the alert monitor and the UI. */
    public Map<String, Object> health(String serviceId) {
        return get(serviceId, "/ops/health", new ParameterizedTypeReference<>() {
        });
    }

    public Map<String, Object> healthOfAll() {
        Map<String, Object> out = new LinkedHashMap<>();
        topology.topology().services().forEach(s -> {
            try {
                out.put(s.id(), health(s.id()));
            } catch (RuntimeException e) {
                out.put(s.id(), Map.of("status", "DOWN", "error", e.getMessage()));
            }
        });
        return out;
    }

    private <T> T get(String serviceId, String path, ParameterizedTypeReference<T> type) {
        String url = baseUrl(serviceId) + path;
        try {
            return http.get().uri(url).retrieve().body(type);
        } catch (RestClientException e) {
            throw new ServiceUnreachableException(serviceId, e);
        }
    }

    private static List<Telemetry.MetricPoint> points(List<Point> points) {
        if (points == null) return List.of();
        return points.stream().map(p -> new Telemetry.MetricPoint(p.timestamp(), p.value())).toList();
    }
}
