package com.noteweave.research;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.noteweave.memory.MemoryControlPackResponse;
import com.noteweave.task.WaitContextResponse;
import com.noteweave.worker.WorkerSourceScopeItemResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ResearchRunDetailResponse(
        String researchRunId,
        String workspaceId,
        String taskId,
        String question,
        String profileKey,
        ResearchIntentResponse researchIntent,
        String resumedFromResearchRunId,
        Integer resumedFromCheckpointNo,
        String status,
        String finalReportTitle,
        String finalReportMarkdown,
        ResearchReportStructureResponse reportStructure,
        ResearchCounterfactualSummaryResponse counterfactualSummary,
        ResearchArtifactCandidateResponse researchArtifactCandidate,
        ResearchReportFileResponse reportFile,
        ResearchRunArtifactResponse researchArtifact,
        ResearchProcessSummaryResponse researchProcessSummary,
        Map<String, Object> harnessControlState,
        Map<String, Object> auditSummaries,
        Map<String, Object> toolboxSummary,
        ResearchResumeContextSummaryResponse resumeContextSummary,
        ResearchResumeCheckpointSummaryResponse resumeCheckpoint,
        ResearchVerifierSummaryResponse verifierSummary,
        String traceSummary,
        List<WorkerSourceScopeItemResponse> sourceScope,
        MemoryControlPackResponse controlPack,
        @JsonProperty("saved_report_source") SaveResearchReportSourceResponse savedReportSource,
        WaitContextResponse waitContext,
        ResearchClosedLoopStateResponse closedLoopState,
        @JsonProperty("agent_execution_projection") Map<String, Object> agentExecutionProjection,
        List<ResearchTraceResponse> traces,
        Instant createdAt,
        Instant updatedAt
) {
}
