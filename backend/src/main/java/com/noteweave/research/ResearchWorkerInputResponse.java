package com.noteweave.research;

import com.noteweave.memory.MemoryControlPackResponse;
import com.noteweave.worker.WorkerContextSnapshotResponse;
import com.noteweave.worker.WorkerSourceScopeItemResponse;
import java.util.List;

public record ResearchWorkerInputResponse(
        String taskId,
        String workspaceId,
        String targetId,
        List<WorkerSourceScopeItemResponse> sourceScope,
        WorkerContextSnapshotResponse contextSnapshot,
        MemoryControlPackResponse controlPack,
        ResearchWorkerInputPayload inputPayload
) {
}
