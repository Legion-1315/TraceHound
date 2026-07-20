package com.db.triage.sim;

import com.db.triage.model.ScenarioDef;
import com.db.triage.model.Telemetry.ChangeEvent;
import com.db.triage.model.Telemetry.LogLine;
import com.db.triage.model.Telemetry.MetricPoint;
import com.db.triage.model.Telemetry.MetricsSummary;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-process simulation of the estate's telemetry. Healthy by default; activating a fault
 * scenario overlays that scenario's log/metric/change-event signature from its start time.
 */
@Service
public class SimulationEngine {

    private static final String[] ISINS = {"DE000BAY0017", "FR0000120271", "NL0011821202", "GB00BH4HKS39",
            "DE0007164600", "IT0003128367", "ES0113900J37"};
    private static final String[] VENUES = {"XETR", "XPAR", "XAMS", "XLON", "XMIL"};

    private static final Map<String, String[]> HEALTHY_TEMPLATES = Map.of(
            "trade-capture", new String[]{
                    "INFO|Trade %TRD% captured venue=%VENUE% isin=%ISIN% qty=%QTY% acct=%ACCT%",
                    "INFO|Published trade %TRD% to estate bus partition=4 offset=%SEQ%",
                    "DEBUG|Venue session %VENUE% heartbeat ok rtt=3ms"},
            "ref-data-service", new String[]{
                    "INFO|GET /v1/currencies 200 in %LATms% caller=enrichment-service",
                    "INFO|GET /v1/lei/%LEI% 200 in %LATms% caller=trade-confirmations",
                    "DEBUG|Golden-source snapshot age 14m within SLA"},
            "enrichment-service", new String[]{
                    "INFO|Enriched trade %TRD% isin=%ISIN% ccy=EUR lei=%LEI% in %LATms%",
                    "INFO|Ref-data cache hit ratio 0.97 window=5m",
                    "DEBUG|Consumer group te-enrich-1 lag=0 partitions=6"},
            "submission-builder", new String[]{
                    "INFO|RTS-22 report built for %TRD% fields=65/65 populated",
                    "INFO|Batch %SEQ% sealed size=250 checksum ok",
                    "DEBUG|Template v2024.11 loaded from registry"},
            "regulatory-gateway", new String[]{
                    "INFO|Submission %TRD% accepted by ARM ack=%SEQ%",
                    "INFO|Outbound TLS session resumed to arm.regulator.example:8443",
                    "DEBUG|Validator pack ESMA-RTS22 v3.2 active"},
            "trade-confirmations", new String[]{
                    "INFO|Confirmation dispatched trade=%TRD% client=%CLIENT% channel=SWIFT",
                    "INFO|GET ref-data lei ok in %LATms%",
                    "DEBUG|Dispatch queue depth 3"});

    private static final Map<String, Double> BASELINE_THROUGHPUT = Map.of(
            "trade-capture", 420.0,
            "ref-data-service", 1150.0,
            "enrichment-service", 415.0,
            "submission-builder", 410.0,
            "regulatory-gateway", 405.0,
            "trade-confirmations", 210.0);

    private final AtomicReference<ActiveScenario> active = new AtomicReference<>();

    private record ActiveScenario(ScenarioDef def, Instant activatedAt) {
    }

    public void activate(ScenarioDef def) {
        active.set(new ActiveScenario(def, Instant.now()));
    }

    public void deactivate() {
        active.set(null);
    }

    public Optional<ScenarioDef> activeScenario() {
        return Optional.ofNullable(active.get()).map(ActiveScenario::def);
    }

    /** Recent log lines for a service, newest first. Fault injections overlay the healthy stream. */
    public List<LogLine> queryLogs(String serviceId, int minutes) {
        Instant now = Instant.now();
        Instant windowStart = now.minus(Duration.ofMinutes(minutes));
        List<LogLine> lines = new ArrayList<>();

        String[] templates = HEALTHY_TEMPLATES.getOrDefault(serviceId, new String[]{"INFO|heartbeat ok"});
        Random rnd = new Random(serviceId.hashCode() * 31L + minutes);
        // healthy chatter roughly every 7s
        for (Instant t = windowStart; t.isBefore(now); t = t.plusSeconds(6 + rnd.nextInt(4))) {
            String template = templates[rnd.nextInt(templates.length)];
            String[] parts = fill(template, rnd).split("\\|", 2);
            lines.add(new LogLine(t, parts[0], corrId(rnd), parts[1]));
        }

        ScenarioDef scenario = activeScenario().orElse(null);
        if (scenario != null && scenario.injections() != null) {
            for (ScenarioDef.Injection inj : scenario.injections()) {
                if (!inj.service().equals(serviceId)) continue;
                Instant faultStart = now.minus(Duration.ofMinutes(inj.fromMinutesAgo()));
                Instant effectiveStart = faultStart.isBefore(windowStart) ? windowStart : faultStart;
                // a stalled/quiet service stops its healthy chatter after the fault starts
                if (inj.metric() != null && inj.metric().throughputFactor() != null
                        && inj.metric().throughputFactor() < 0.1) {
                    lines.removeIf(l -> l.timestamp().isAfter(effectiveStart) && !l.level().equals("ERROR"));
                }
                if (inj.logs() == null) continue;
                for (ScenarioDef.InjectedLog log : inj.logs()) {
                    int step = Math.max(1, log.everySeconds());
                    for (Instant t = effectiveStart; t.isBefore(now); t = t.plusSeconds(step)) {
                        lines.add(new LogLine(t, log.level(), corrId(rnd), fill(log.message(), rnd)));
                    }
                }
            }
        }

        lines.sort(Comparator.comparing(LogLine::timestamp).reversed());
        return lines.size() > 120 ? lines.subList(0, 120) : lines;
    }

    /** Current metrics summary with 60-minute series. */
    public MetricsSummary queryMetrics(String serviceId) {
        Instant now = Instant.now();
        double baseline = BASELINE_THROUGHPUT.getOrDefault(serviceId, 300.0);
        Random rnd = new Random(serviceId.hashCode());

        double faultErrorRate = -1;
        double throughputFactor = 1.0;
        long faultLatency = -1;
        int faultFromMin = -1;

        ScenarioDef scenario = activeScenario().orElse(null);
        if (scenario != null && scenario.injections() != null) {
            for (ScenarioDef.Injection inj : scenario.injections()) {
                if (!inj.service().equals(serviceId) || inj.metric() == null) continue;
                faultFromMin = inj.fromMinutesAgo();
                if (inj.metric().errorRate() != null) faultErrorRate = inj.metric().errorRate();
                if (inj.metric().throughputFactor() != null) throughputFactor = inj.metric().throughputFactor();
                if (inj.metric().latencyMs() != null) faultLatency = inj.metric().latencyMs();
            }
        }

        List<MetricPoint> errSeries = new ArrayList<>();
        List<MetricPoint> tputSeries = new ArrayList<>();
        for (int m = 60; m >= 0; m--) {
            Instant t = now.minus(Duration.ofMinutes(m));
            boolean inFault = faultFromMin >= 0 && m <= faultFromMin;
            double err = inFault && faultErrorRate >= 0
                    ? faultErrorRate + rnd.nextDouble() * 0.02
                    : 0.002 + rnd.nextDouble() * 0.004;
            double tput = baseline * (inFault ? throughputFactor : 1.0) * (0.95 + rnd.nextDouble() * 0.1);
            errSeries.add(new MetricPoint(t, round4(err)));
            tputSeries.add(new MetricPoint(t, Math.round(tput)));
        }

        boolean inFaultNow = faultFromMin >= 0;
        double currentErr = inFaultNow && faultErrorRate >= 0 ? faultErrorRate : 0.004;
        double currentTput = baseline * (inFaultNow ? throughputFactor : 1.0);
        long latency = inFaultNow && faultLatency >= 0 ? faultLatency : 40 + rnd.nextInt(60);

        return new MetricsSummary(serviceId, round4(currentErr), Math.round(currentTput), baseline, latency,
                errSeries, tputSeries);
    }

    /** Change events across the given services in the window; scenario-planted events included. */
    public List<ChangeEvent> changeEvents(List<String> serviceIds, int minutes) {
        Instant now = Instant.now();
        List<ChangeEvent> events = new ArrayList<>();
        ScenarioDef scenario = activeScenario().orElse(null);
        if (scenario != null && scenario.changeEvents() != null) {
            for (ScenarioDef.PlantedChange pc : scenario.changeEvents()) {
                if (pc.minutesAgo() <= minutes && (serviceIds.isEmpty() || serviceIds.contains(pc.service()))) {
                    events.add(new ChangeEvent(now.minus(Duration.ofMinutes(pc.minutesAgo())), pc.service(),
                            pc.type(), pc.summary(), pc.author()));
                }
            }
        }
        events.sort(Comparator.comparing(ChangeEvent::timestamp).reversed());
        return events;
    }

    /** Pre/post-deploy schema diff for a service, if the active scenario planted one. */
    public Optional<ScenarioDef.SchemaDiff> schemaDiff(String serviceId) {
        return activeScenario()
                .map(ScenarioDef::schemaDiff)
                .filter(d -> d != null && d.service().equals(serviceId));
    }

    private String fill(String template, Random rnd) {
        return template
                .replace("%TRD%", "TR-" + (700000 + rnd.nextInt(90000)))
                .replace("{seq}", String.valueOf(500000 + rnd.nextInt(90000)))
                .replace("%SEQ%", String.valueOf(500000 + rnd.nextInt(90000)))
                .replace("%ISIN%", ISINS[rnd.nextInt(ISINS.length)])
                .replace("{isin}", ISINS[rnd.nextInt(ISINS.length)])
                .replace("%VENUE%", VENUES[rnd.nextInt(VENUES.length)])
                .replace("%QTY%", String.valueOf(100 * (1 + rnd.nextInt(50))))
                .replace("%ACCT%", "ACC-" + (10000000 + rnd.nextInt(89999999)))
                .replace("{acct}", "ACC-" + (10000000 + rnd.nextInt(89999999)))
                .replace("%CLIENT%", "CLI-" + (100000 + rnd.nextInt(899999)))
                .replace("{client}", "CLI-" + (100000 + rnd.nextInt(899999)))
                .replace("%LEI%", "5299" + (100000000 + rnd.nextInt(899999999)) + "N")
                .replace("%LATms%", (2 + rnd.nextInt(18)) + "ms");
    }

    private String corrId(Random rnd) {
        return String.format("c-%08x", rnd.nextInt());
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
