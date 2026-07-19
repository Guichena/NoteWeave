package com.noteweave.research;

import com.noteweave.memory.MemoryControlPackResponse;
import com.noteweave.worker.WorkerSourceScopeItemResponse;
import java.util.List;

public record ResearchWorkerInputResponse(
        String taskId,
        String workspaceId,
        String targetId,
        int attemptNo,
        long fencingToken,
        List<WorkerSourceScopeItemResponse> sourceScope,
        String retrievalMode,
        MemoryControlPackResponse controlPack,
        ResearchWorkerInputPayload inputPayload
) {
}
