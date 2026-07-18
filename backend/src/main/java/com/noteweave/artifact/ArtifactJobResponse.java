package com.noteweave.artifact;

public record ArtifactJobResponse(
        String artifactJobId,
        String taskId,
        String skillKey,
        String status
) {
}
