package com.noteweave.artifact;

import com.noteweave.task.WaitContextResponse;
import java.time.Instant;

public record ArtifactJobSummaryResponse(
        String artifactJobId,
        String workspaceId,
        String taskId,
        String skillKey,
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
