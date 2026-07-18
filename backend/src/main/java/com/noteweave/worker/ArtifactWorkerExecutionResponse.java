package com.noteweave.worker;

public record ArtifactWorkerExecutionResponse(
        String taskId,
        String status,
        int progressEvents,
        String resultTitle
) {
}
