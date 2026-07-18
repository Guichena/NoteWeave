package com.noteweave.artifact;

import com.noteweave.task.WaitContextResponse;
import java.time.Instant;
import java.util.Map;

public record ArtifactJobDetailResponse(
        String artifactJobId,
        String workspaceId,
        String taskId,
        String skillKey,
        String userRequirement,
        Map<String, Object> inputs,
        String status,
        String taskStatus,
        String progressPhase,
        String progressMessage,
        String resultTitle,
        WaitContextResponse waitContext,
        int latestVersionNo,
        Instant createdAt,
        Instant updatedAt
) {
}
