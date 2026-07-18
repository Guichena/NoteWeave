package com.noteweave.research;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;

public record ResearchCheckpointResponse(
        int checkpointNo,
        String snapshotType,
        String objectKey,
        String payloadSha256,
        long contentSize,
        String activeBranchId,
        String finalLoopDecision,
        ResearchCheckpointSnapshotSummaryResponse summary,
        ResearchCounterfactualSummaryResponse counterfactualSummary,
        ResearchReportFileResponse reportFile,
        ResearchRunArtifactResponse researchArtifact,
        @JsonProperty("saved_report_source")
        SaveResearchReportSourceResponse savedReportSource,
        ResearchProcessSummaryResponse researchProcessSummary,
        Map<String, Object> harnessControlState,
        Map<String, Object> auditSummaries,
        Map<String, Object> toolboxSummary,
        Map<String, Object> payload,
        Instant createdAt
) {
}
