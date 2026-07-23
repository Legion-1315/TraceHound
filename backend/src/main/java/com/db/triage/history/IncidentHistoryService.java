package com.db.triage.history;

import com.db.triage.model.ScenarioDef;
import com.db.triage.model.TriageReport;
import com.db.triage.sim.ScenarioService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

/**
 * Historical incident store powering the Analytics tab. On first run it seeds a corpus of
 * realistic past incidents; every completed investigation is appended so the dashboards grow
 * over time. Persists to {@code triage.history-file} as JSON.
 */
@Service
public class IncidentHistoryService {

    private static final int SEED_COUNT = 30;

    /** Friendly service names keyed by topology service id. */
    private static final Map<String, String> SERVICE_DISPLAY = Map.of(
            "trade-capture", "Trade Capture",
            "ref-data-service", "Reference Data",
            "enrichment-service", "Enrichment",
            "submission-builder", "Submission Builder",
            "regulatory-gateway", "Gateway",
            "trade-confirmations", "Trade Confirmations");

    /** How heavily each scenario appears in the seeded corpus (organic-looking mix). */
    private static final Map<String, Integer> SEED_WEIGHTS = Map.of(
            "schema-drift", 6,
            "consumer-lag", 5,
            "cert-expiry", 4,
            "latency-spike", 5,
            "duplicate-trades", 4,
            "thread-pool-exhaustion", 4);

    private static final int SEED_CRITICAL = 4;    // ~13% SEV1
    private static final int SEED_UNRESOLVED = 2;  // ~93% AI success

    private final File store;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ScenarioService scenarios;
    private final List<IncidentHistory> incidents = new ArrayList<>();

    public IncidentHistoryService(@Value("${triage.history-file}") String historyFile,
                                  ScenarioService scenarios) {
        this.store = new File(historyFile);
        this.scenarios = scenarios;
        load();
        if (incidents.isEmpty()) {
            seed();
            persist();
        }
    }

    private void load() {
        if (!store.exists()) return;
        try {
            IncidentHistory[] loaded = json.readValue(store, IncidentHistory[].class);
            incidents.addAll(List.of(loaded));
        } catch (IOException ignored) {
            // corrupt store: fall through to a fresh seed, demo must never crash
        }
    }

    /** Deterministically fabricate ~30 past incidents spread over the last ~90 days. */
    private void seed() {
        List<ScenarioDef> defs = scenarios.all();
        if (defs.isEmpty()) return;
        Random rnd = new Random(20260721L); // fixed seed => stable dashboards across restarts
        Instant now = Instant.now();

        // Allocate by weight rather than drawing at random, so every scenario (and therefore
        // every category and service) is guaranteed to appear in the charts.
        List<String> allocation = weightedAllocation(defs, SEED_COUNT);
        java.util.Collections.shuffle(allocation, rnd);

        // Fixed counts keep the headline KPIs stable and demo-friendly.
        Set<Integer> criticalIdx = pickDistinct(rnd, SEED_CRITICAL, allocation.size());
        Set<Integer> unresolvedIdx = pickDistinct(rnd, SEED_UNRESOLVED, allocation.size());

        for (int i = 0; i < allocation.size(); i++) {
            ScenarioDef def = scenarios.byId(allocation.get(i)).orElse(defs.get(0));

            // spread across the last ~80 days, skewed toward the recent past so the
            // trailing weeks of the trend chart are populated
            long minutesAgo = (long) (rnd.nextDouble() * rnd.nextDouble() * 80 * 24 * 60);
            Instant ts = now.minus(minutesAgo, ChronoUnit.MINUTES);

            boolean critical = criticalIdx.contains(i);
            boolean aiResolved = !unresolvedIdx.contains(i);
            long mttr = 4 * 60 + (long) (rnd.nextDouble() * 12 * 60);  // 4-16 min
            if (critical) mttr += 3 * 60;
            if (!aiResolved) mttr += 9 * 60;                            // human escalation takes longer
            int baseConfidence = def.report() != null ? def.report().confidence() : 88;
            int confidence = aiResolved ? clamp(baseConfidence - 6 + rnd.nextInt(12), 70, 99) : 0;
            int toolCalls = 6 + rnd.nextInt(7);
            String rootService = rootService(def);

            incidents.add(new IncidentHistory(
                    "INC-" + (200 + i),
                    ts,
                    def.id(),
                    categoryOf(def),
                    rootService,
                    SERVICE_DISPLAY.getOrDefault(rootService, rootService),
                    critical ? "SEV1" : "SEV2",
                    critical,
                    mttr,
                    aiResolved,
                    confidence,
                    toolCalls));
        }
        incidents.sort((a, b) -> b.occurredAt().compareTo(a.occurredAt()));
    }

    /** Largest-remainder allocation of {@code total} incidents across scenarios by weight. */
    private List<String> weightedAllocation(List<ScenarioDef> defs, int total) {
        int weightSum = defs.stream().mapToInt(d -> SEED_WEIGHTS.getOrDefault(d.id(), 3)).sum();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Double> remainders = new LinkedHashMap<>();
        int assigned = 0;
        for (ScenarioDef def : defs) {
            double exact = (double) total * SEED_WEIGHTS.getOrDefault(def.id(), 3) / weightSum;
            int floor = (int) Math.floor(exact);
            counts.put(def.id(), floor);
            remainders.put(def.id(), exact - floor);
            assigned += floor;
        }
        // hand out the leftovers to the largest fractional parts
        List<String> byRemainder = remainders.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        for (int i = 0; assigned < total && !byRemainder.isEmpty(); i++, assigned++) {
            String id = byRemainder.get(i % byRemainder.size());
            counts.merge(id, 1, Integer::sum);
        }

        List<String> allocation = new ArrayList<>();
        counts.forEach((id, n) -> {
            for (int i = 0; i < n; i++) allocation.add(id);
        });
        return allocation;
    }

    private Set<Integer> pickDistinct(Random rnd, int howMany, int bound) {
        Set<Integer> picked = new LinkedHashSet<>();
        while (picked.size() < Math.min(howMany, bound)) {
            picked.add(rnd.nextInt(bound));
        }
        return picked;
    }

    /** Append a completed real investigation and persist. Severity is read from the alert text. */
    public synchronized void record(String incidentId, String scenarioId, TriageReport report, String alertText) {
        ScenarioDef def = scenarios.byId(scenarioId).orElse(null);
        boolean critical = alertText != null && alertText.toUpperCase(Locale.ROOT).contains("SEV1");
        String rootService = def != null ? rootService(def) : "regulatory-gateway";

        IncidentHistory history = new IncidentHistory(
                incidentId,
                Instant.now(),
                scenarioId,
                def != null ? categoryOf(def) : "Other",
                rootService,
                SERVICE_DISPLAY.getOrDefault(rootService, rootService),
                critical ? "SEV1" : "SEV2",
                critical,
                Math.max(1, report.elapsedMs() / 1000),
                !report.inconclusive(),
                report.inconclusive() ? 0 : report.confidencePct(),
                report.toolCallCount());

        incidents.add(0, history);
        persist();
    }

    public synchronized List<IncidentHistory> all() {
        return List.copyOf(incidents);
    }

    /** Aggregate the corpus into the shape the Analytics dashboards consume. */
    public synchronized DashboardResponse dashboard() {
        int total = incidents.size();
        int critical = (int) incidents.stream().filter(IncidentHistory::critical).count();
        long aiResolved = incidents.stream().filter(IncidentHistory::aiResolved).count();
        double avgMttrSec = incidents.stream().mapToLong(IncidentHistory::mttrSeconds).average().orElse(0);

        DashboardResponse.Summary summary = new DashboardResponse.Summary(
                total,
                String.format(Locale.ROOT, "%.1f mins", avgMttrSec / 60.0),
                total == 0 ? "0%" : Math.round(100.0 * aiResolved / total) + "%",
                critical);

        return new DashboardResponse(
                summary,
                countBy(IncidentHistory::category),
                countBy(IncidentHistory::serviceName),
                weeklyTrend(),
                List.of(new DashboardResponse.ChartEntry("SEV1", critical),
                        new DashboardResponse.ChartEntry("SEV2", total - critical)));
    }

    private List<DashboardResponse.ChartEntry> countBy(Function<IncidentHistory, String> key) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (IncidentHistory i : incidents) {
            counts.merge(key.apply(i), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .map(e -> new DashboardResponse.ChartEntry(e.getKey(), e.getValue()))
                .toList();
    }

    /**
     * Incident counts bucketed into the last 8 weeks, oldest first, for a trend chart.
     * Windows look backwards from now so the final bucket is the current week, not the future.
     */
    private List<DashboardResponse.ChartEntry> weeklyTrend() {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMM d", Locale.ROOT);
        Instant now = Instant.now();
        List<DashboardResponse.ChartEntry> buckets = new ArrayList<>();
        for (int w = 7; w >= 0; w--) {
            Instant weekEnd = now.minus((long) w * 7, ChronoUnit.DAYS);
            Instant weekStart = weekEnd.minus(7, ChronoUnit.DAYS);
            int count = (int) incidents.stream()
                    .filter(i -> !i.occurredAt().isBefore(weekStart) && i.occurredAt().isBefore(weekEnd))
                    .count();
            String label = weekStart.atZone(ZoneOffset.UTC).toLocalDate().format(fmt);
            buckets.add(new DashboardResponse.ChartEntry(label, count));
        }
        return buckets;
    }

    private String rootService(ScenarioDef def) {
        return def.report() != null && def.report().rootCauseService() != null
                ? def.report().rootCauseService()
                : "regulatory-gateway";
    }

    private String categoryOf(ScenarioDef def) {
        return def.category() == null || def.category().isBlank() ? "Other" : def.category();
    }

    private void persist() {
        try {
            if (store.getParentFile() != null) store.getParentFile().mkdirs();
            json.writerWithDefaultPrettyPrinter().writeValue(store, incidents);
        } catch (IOException ignored) {
            // persistence failure must never break an investigation or the dashboard
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
