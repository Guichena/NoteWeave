package com.noteweave.research;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record ResearchResumeCheckpointSummaryResponse(
        String sourceResearchRunId,
        int checkpointNo,
        String snapshotType,
        String activeBranchId,
        String finalLoopDecision,
        ResearchReportFileResponse reportFile,
        ResearchRunArtifactResponse researchArtifact,
        @JsonProperty("saved_report_source")
        SaveResearchReportSourceResponse savedReportSource,
        ResearchCheckpointSnapshotSummaryResponse summary,
        ResearchCounterfactualSummaryResponse counterfactualSummary,
        ResearchRecoveryTargetsResponse recoveryTargets,
        Instant createdAt
) {
}
