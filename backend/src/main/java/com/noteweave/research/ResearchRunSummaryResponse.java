package com.noteweave.research;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.noteweave.task.WaitContextResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ResearchRunSummaryResponse(
        String researchRunId,
        String taskId,
        String question,
        String profileKey,
        String status,
        String finalReportTitle,
        String resumedFromResearchRunId,
        Integer resumedFromCheckpointNo,
        int sourceScopeCount,
        int checkpointCount,
        String activeBranchId,
        String localVerifierStatus,
        String localVerifierReason,
        int ledgerRowCount,
        int verifiedRowCount,
        int conflictedRowCount,
        int blockedRowCount,
        int guardrailedRowCount,
        int recoveryTargetedBlockedRowCount,
        int uncoveredBlockedRowCount,
        int requirementPartialBlockedRowCount,
        String globalVerifierDecision,
        String globalVerifierReason,
        String finalLoopDecision,
        String finalLoopReason,
        String recoveryMode,
        String researchIntentAlignmentStatus,
        String researchIntentAlignmentReason,
        int intentSatisfiedConstraintCount,
        int intentConstraintCount,
        int intentSatisfiedRequirementCount,
        int intentRequirementCount,
        int intentPendingRequirementCount,
        List<String> missingIntentRequirements,
        ResearchResumeCheckpointSummaryResponse resumeCheckpoint,
        ResearchArtifactCandidateResponse researchArtifactCandidate,
        ResearchReportFileResponse reportFile,
        ResearchRunArtifactResponse researchArtifact,
        ResearchProcessSummaryResponse researchProcessSummary,
        Map<String, Object> harnessControlState,
        Map<String, Object> auditSummaries,
        Map<String, Object> toolboxSummary,
        @JsonProperty("saved_report_source") SaveResearchReportSourceResponse savedReportSource,
        WaitContextResponse waitContext,
        ResearchRecoveryTargetsResponse recoveryTargets,
        ResearchCounterfactualSummaryResponse counterfactualSummary,
        Instant createdAt,
        Instant updatedAt
) {
}
