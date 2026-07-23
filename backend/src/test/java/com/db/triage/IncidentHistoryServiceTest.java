package com.db.triage;

import com.db.triage.history.DashboardResponse;
import com.db.triage.history.IncidentHistory;
import com.db.triage.history.IncidentHistoryService;
import com.db.triage.model.TriageReport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Analytics tab is only as good as this corpus: it must seed on first run,
 * aggregate correctly, and grow when a real investigation completes.
 */
@SpringBootTest(properties = {
        "triage.step-delay-min-ms=1",
        "triage.step-delay-max-ms=2",
        "triage.memory-file=build/test-data/hist-memory.json",
        "triage.history-file=build/test-data/hist-history.json"
})
class IncidentHistoryServiceTest {

    @Autowired
    IncidentHistoryService history;

    @BeforeAll
    static void clearStore() {
        // force a fresh seed so the assertions below are deterministic
        new File("build/test-data/hist-history.json").delete();
    }

    @Test
    void seedsAHistoricalCorpusOnFirstRun() {
        List<IncidentHistory> all = history.all();
        assertTrue(all.size() >= 30, "expected a seeded corpus of ~30, got " + all.size());
        for (IncidentHistory h : all) {
            assertNotNull(h.category());
            assertNotEquals("Other", h.category(), "seeded incident has an unmapped category: " + h.scenarioId());
            assertNotNull(h.serviceName());
            assertFalse(h.serviceName().contains("-"),
                    "serviceName should be a display name, not a service id: " + h.serviceName());
            assertTrue(h.mttrSeconds() > 0);
        }
    }

    @Test
    void dashboardAggregatesCategoriesServicesAndSummary() {
        DashboardResponse dash = history.dashboard();

        assertEquals(history.all().size(), dash.summary().totalIncidents());
        assertTrue(dash.summary().avgMttr().endsWith("mins"), dash.summary().avgMttr());
        assertTrue(dash.summary().aiSuccess().endsWith("%"), dash.summary().aiSuccess());

        // every incident must land in exactly one category bucket and one service bucket
        int categoryTotal = dash.categories().stream().mapToInt(DashboardResponse.ChartEntry::value).sum();
        int serviceTotal = dash.services().stream().mapToInt(DashboardResponse.ChartEntry::value).sum();
        assertEquals(dash.summary().totalIncidents(), categoryTotal, "category counts must sum to total");
        assertEquals(dash.summary().totalIncidents(), serviceTotal, "service counts must sum to total");

        // buckets arrive sorted descending so the charts read well without client-side sorting
        assertTrue(isDescending(dash.categories()), "categories must be sorted desc");
        assertTrue(isDescending(dash.services()), "services must be sorted desc");

        assertEquals(8, dash.trend().size(), "trend should cover 8 weekly buckets");
        assertEquals(2, dash.severity().size());
    }

    @Test
    void everyScenarioCategoryAndServiceIsRepresentedInTheCharts() {
        DashboardResponse dash = history.dashboard();
        // a category or service missing entirely would leave a hole in the pie/bar charts
        assertEquals(5, dash.categories().size(),
                "expected all 5 categories represented, got " + dash.categories());
        assertEquals(5, dash.services().size(),
                "expected all 5 root-cause services represented, got " + dash.services());
        assertTrue(dash.categories().stream().anyMatch(c -> c.name().equals("Data Consistency")));
        assertTrue(dash.services().stream().anyMatch(c -> c.name().equals("Trade Capture")));
    }

    @Test
    void trendWindowsLookBackwardsSoTheCurrentWeekIsPopulated() {
        DashboardResponse dash = history.dashboard();
        int trendTotal = dash.trend().stream().mapToInt(DashboardResponse.ChartEntry::value).sum();
        assertTrue(trendTotal > 0, "the 8-week trend must contain incidents");
        // the last bucket is the week ending now; with recent-skewed seeding it must not be empty
        assertTrue(dash.trend().get(dash.trend().size() - 1).value() > 0,
                "final trend bucket (current week) should not be empty: " + dash.trend());
    }

    @Test
    void recordingACompletedInvestigationGrowsTheCorpus() {
        int before = history.all().size();
        TriageReport report = new TriageReport("INC-HIST-1", "root cause text", 91, "summary",
                List.of(), List.of(), "Reference Data Platform", "#refdata-support",
                List.of("fix it"), List.of(), 9000, 8, 12, false);

        history.record("INC-HIST-1", "schema-drift", report, "ALERT [SEV1] something bad");

        List<IncidentHistory> all = history.all();
        assertEquals(before + 1, all.size());

        IncidentHistory newest = all.get(0);
        assertEquals("INC-HIST-1", newest.incidentId());
        assertEquals("Data Contract", newest.category(), "category must come from the scenario definition");
        assertEquals("Reference Data", newest.serviceName());
        assertTrue(newest.critical(), "SEV1 in the alert text must mark the incident critical");
        assertEquals(9, newest.mttrSeconds(), "mttr derives from the report's elapsed time");
        assertTrue(newest.aiResolved());
        assertEquals(91, newest.confidence());

        // and the dashboard reflects it immediately
        assertEquals(all.size(), history.dashboard().summary().totalIncidents());
    }

    private boolean isDescending(List<DashboardResponse.ChartEntry> entries) {
        for (int i = 1; i < entries.size(); i++) {
            if (entries.get(i - 1).value() < entries.get(i).value()) return false;
        }
        return true;
    }
}
