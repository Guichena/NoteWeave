package com.noteweave.research;

public record ResearchAuditSummaryResponse(
        String localVerifierStatus,
        String globalVerifierDecision,
        String finalLoopDecision,
        boolean hasCounterfactualRecheck,
        int counterfactualBranchCount,
        int checkpointCount,
        int blockedRowCount,
        int conflictedRowCount,
        int guardrailedRowCount,
        int recoveryTargetCount
) {
}
