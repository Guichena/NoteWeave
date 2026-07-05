package com.noteweave.artifact;

import com.noteweave.memory.MemoryControlPackResponse;
import com.noteweave.worker.WorkerContextSnapshotResponse;
import com.noteweave.worker.WorkerSourceScopeItemResponse;
import java.util.List;

public record ArtifactWorkerInputResponse(
        String taskId,
        String workspaceId,
        String targetId,
        List<WorkerSourceScopeItemResponse> sourceScope,
        WorkerContextSnapshotResponse contextSnapshot,
        MemoryControlPackResponse controlPack,
        ArtifactWorkerInputPayload inputPayload
) {
}
