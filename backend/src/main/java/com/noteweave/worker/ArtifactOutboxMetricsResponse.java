package com.noteweave.worker;

public record ArtifactOutboxMetricsResponse(
        int readyCount,
        int processingCount,
        int sentCount,
        int deadLetterCount,
        int retryingCount,
        int exhaustedCount
) {
}
