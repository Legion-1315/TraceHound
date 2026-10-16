package com.db.triage.model;

import java.util.List;

/**
 * The outcome of an investigation.
 *
 * @param outcome          ROOT_CAUSE_FOUND, INCONCLUSIVE, NO_FAULT_FOUND or OUT_OF_SCOPE
 * @param rootCauseService service id the root cause sits in, or null when there is none
 * @param mode             "llm" (live Claude agent) or "scripted" (deterministic replay)
 * @param usage            model, tokens and estimated cost — null for scripted runs
 */
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
        boolean inconclusive,
        String outcome,
        String rootCauseService,
        String mode,
        AgentUsage usage) {

    public static final String ROOT_CAUSE_FOUND = "ROOT_CAUSE_FOUND";
    public static final String INCONCLUSIVE = "INCONCLUSIVE";
    public static final String NO_FAULT_FOUND = "NO_FAULT_FOUND";
    public static final String OUT_OF_SCOPE = "OUT_OF_SCOPE";

    /** Pre-live-estate shape (kept for the scripted path and older tests). */
    public TriageReport(String incidentId, String rootCause, int confidencePct, String summary,
                        List<CausalLink> causalChain, List<BlastRadiusEntry> blastRadius, String owningTeam,
                        String owningSlack, List<String> remediation, List<String> ruledOut, long elapsedMs,
                        int toolCallCount, int piiRedactedCount, boolean inconclusive) {
        this(incidentId, rootCause, confidencePct, summary, causalChain, blastRadius, owningTeam, owningSlack,
                remediation, ruledOut, elapsedMs, toolCallCount, piiRedactedCount, inconclusive,
                inconclusive ? INCONCLUSIVE : ROOT_CAUSE_FOUND, null, "scripted", null);
    }

    public record CausalLink(String claim, long ledgerId) {
    }

    public record BlastRadiusEntry(String flowId, String flowName, String criticality, String impactedService,
                                   String owningTeam) {
    }
}
