package com.noteweave.research;

import com.noteweave.memory.MemoryControlPackResponse;
import com.noteweave.worker.WorkerSourceScopeItemResponse;
import java.time.Instant;
import java.util.List;

public record ResearchRunDetailResponse(
        String researchRunId,
        String workspaceId,
        String taskId,
        String question,
        String profileKey,
        String contextSnapshotId,
        String status,
        String finalReportTitle,
        String finalReportMarkdown,
        String traceSummary,
        List<WorkerSourceScopeItemResponse> sourceScope,
        MemoryControlPackResponse controlPack,
        List<ResearchTraceResponse> traces,
        Instant createdAt,
        Instant updatedAt
) {
}
