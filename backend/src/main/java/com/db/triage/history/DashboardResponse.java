package com.db.triage.history;

import java.util.List;

public record DashboardResponse(

        Summary summary,

        List<ChartEntry> categories,

        List<ChartEntry> services,

        List<ChartEntry> trend,

        List<ChartEntry> severity

) {

    public record Summary(
            int totalIncidents,
            String avgMttr,
            String aiSuccess,
            int criticalIncidents
    ) {
    }

    public record ChartEntry(
            String name,
            int value
    ) {
    }

}
