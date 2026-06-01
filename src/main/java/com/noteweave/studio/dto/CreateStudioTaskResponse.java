package com.noteweave.studio.dto;

import com.noteweave.artifact.model.ArtifactStatus;
import com.noteweave.task.model.TaskStatus;
import lombok.Builder;

@Builder
public record CreateStudioTaskResponse(
        Long taskId,
        Long artifactId,
        Long artifactSpaceId,
        TaskStatus taskStatus,
        ArtifactStatus artifactStatus
) {
}
