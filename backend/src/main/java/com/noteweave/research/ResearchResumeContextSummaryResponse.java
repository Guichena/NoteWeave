package com.noteweave.research;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResearchResumeContextSummaryResponse(
        String sourceResearchRunId,
        int checkpointNo,
        String snapshotType,
        String activeBranchId,
        String finalLoopDecision,
        int restoredSearchHitCount,
        int restoredReadWindowCount,
        int restoredEvidenceCardCount,
        int restoredLoopRoundCount,
        int restoredToolTraceCount
) {
}
