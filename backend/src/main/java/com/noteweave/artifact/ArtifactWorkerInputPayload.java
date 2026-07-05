package com.noteweave.artifact;

public record ArtifactWorkerInputPayload(
        String actionKey,
        String styleProfileKey,
        String contextSnapshotId
) {
}
