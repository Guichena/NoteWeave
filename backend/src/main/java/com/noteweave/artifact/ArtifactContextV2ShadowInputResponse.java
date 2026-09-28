package com.noteweave.artifact;

import java.util.List;

/** Read-only shadow identity. Worker generation continues to use artifact-input-v1. */
public record ArtifactContextV2ShadowInputResponse(
        String snapshotId,
        String projectionSha256,
        String compilerVersion,
        List<String> memoryRevisionIds,
        String replayAvailability
) {
    public ArtifactContextV2ShadowInputResponse {
        memoryRevisionIds = List.copyOf(memoryRevisionIds);
    }
}
