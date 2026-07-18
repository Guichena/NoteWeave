package com.noteweave.worker;

public record ArtifactOutboxDispatchResponse(
        int dispatchedCount
) {
}
