package com.noteweave.artifact;

import com.noteweave.memory.MemoryControlPackResponse;
import com.noteweave.worker.WorkerContextSnapshotResponse;
import com.noteweave.worker.WorkerSourceScopeItemResponse;
import java.util.List;

public record ArtifactWorkerInputResponse(
        String taskId,
        String workspaceId,
        String targetId,
        String inputSnapshotId,
        String replayAvailability,
        List<WorkerSourceScopeItemResponse> sourceScope,
        List<ArtifactUpstreamRefRequest> upstreamRefs,
        WorkerContextSnapshotResponse contextSnapshot,
        MemoryControlPackResponse controlPack,
        ArtifactWorkerInputPayload inputPayload
) {
}
