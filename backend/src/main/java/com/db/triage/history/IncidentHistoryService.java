package com.db.triage.history;

import com.db.triage.model.TriageReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class IncidentHistoryService {

    private final File store;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private final List<IncidentHistory> incidents = new ArrayList<>();

    public IncidentHistoryService(
            @Value("${triage.history-file}") String historyFile) {

        this.store = new File(historyFile);

        if (store.exists()) {
            try {
                IncidentHistory[] loaded =
                        json.readValue(store, IncidentHistory[].class);

                incidents.addAll(List.of(loaded));

            } catch (IOException ignored) {
                // demo must never fail because of history
            }
        }
    }

    public synchronized void record(
            String incidentId,
            String scenarioId,
            TriageReport report) {

        IncidentHistory history = new IncidentHistory(
                incidentId,
                Instant.now(),
                scenarioId,
                category(scenarioId),
                report.rootCause(),
                report.confidencePct(),
                report.toolCallCount()
        );

        incidents.add(history);

        try {

            store.getParentFile().mkdirs();

            json.writerWithDefaultPrettyPrinter()
                    .writeValue(store, incidents);

        } catch (IOException ignored) {
        }
    }

    public synchronized DashboardResponse dashboard() {

        Map<String, Long> categoryCounts =
                incidents.stream()
                        .collect(Collectors.groupingBy(
                                IncidentHistory::category,
                                Collectors.counting()));

        Map<String, Long> serviceCounts =
        incidents.stream()
                .collect(Collectors.groupingBy(
                        i -> serviceName(i.rootCause()),
                        Collectors.counting()));


        List<DashboardResponse.ChartEntry> categories =
                categoryCounts.entrySet()
                        .stream()
                        .map(e -> new DashboardResponse.ChartEntry(
                                e.getKey(),
                                e.getValue().intValue()))
                        .sorted((a, b) -> Integer.compare(b.value(), a.value()))
                        .toList();

        List<DashboardResponse.ChartEntry> services =
                serviceCounts.entrySet()
                        .stream()
                        .map(e -> new DashboardResponse.ChartEntry(
                                e.getKey(),
                                e.getValue().intValue()))
                        .sorted((a, b) -> Integer.compare(b.value(), a.value()))
                        .toList();

        int total = incidents.size();

        long avgTools =
                incidents.stream()
                        .mapToInt(IncidentHistory::toolCalls)
                        .sum();

        String avgMttr =
                total == 0
                        ? "0 mins"
                        : String.format("%.1f mins",
                        (double) avgTools / total);

        long aiSuccess =
                incidents.stream()
                        .filter(i -> i.confidence() >= 90)
                        .count();

        DashboardResponse.Summary summary =
                new DashboardResponse.Summary(

                        total,

                        avgMttr,

                        total == 0
                                ? "0%"
                                : Math.round(
                                aiSuccess * 100.0 / total) + "%",

                        (int) incidents.stream()
                                .filter(i -> i.confidence() >= 90)
                                .count()

                );

        return new DashboardResponse(
                summary,
                categories,
                services
        );
    }

    private String serviceName(String rootCause) {

    if (rootCause.contains("Reference Data"))
        return "Reference Data Service";

    if (rootCause.contains("Regulatory Gateway"))
        return "Regulatory Gateway";

    if (rootCause.contains("Enrichment"))
        return "Enrichment Service";

    if (rootCause.contains("Submission Builder"))
        return "Submission Builder";

    if (rootCause.contains("Trade Capture"))
        return "Trade Capture";

    return "Other";
}

    private String category(String scenarioId) {

        return switch (scenarioId) {

            case "schema-drift" -> "Data Contract";

            case "consumer-lag" -> "Messaging";

            case "cert-expiry" -> "Infrastructure";

            case "cache-staleness" -> "Data Consistency";

            case "database-latency" -> "Performance";

            default -> "Other";
        };
    }

}