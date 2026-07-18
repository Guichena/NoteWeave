package com.noteweave.research;

import java.time.Instant;

public record ResearchCheckpointSummaryResponse(
        int checkpointNo,
        String snapshotType,
        String activeBranchId,
        String finalLoopDecision,
        String localVerifierStatus,
        String globalVerifierDecision,
        int verifiedRowCount,
        int conflictedRowCount,
        ResearchCheckpointSnapshotSummaryResponse summary,
        ResearchCounterfactualSummaryResponse counterfactualSummary,
        ResearchRecoveryTargetsResponse recoveryTargets,
        Instant createdAt
) {
}
