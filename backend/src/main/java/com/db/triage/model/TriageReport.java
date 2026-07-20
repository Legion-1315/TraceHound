package com.db.triage.model;

import java.util.List;

public record TriageReport(
        String incidentId,
        String rootCause,
        int confidencePct,
        String summary,
        List<CausalLink> causalChain,
        List<BlastRadiusEntry> blastRadius,
        String owningTeam,
        String owningSlack,
        List<String> remediation,
        List<String> ruledOut,
        long elapsedMs,
        int toolCallCount,
        int piiRedactedCount,
        boolean inconclusive) {

    public record CausalLink(String claim, long ledgerId) {
    }

    public record BlastRadiusEntry(String flowId, String flowName, String criticality, String impactedService,
                                   String owningTeam) {
    }
}
