package com.noteweave.worker;

import java.time.Instant;

public record ArtifactOutboxDeadLetterResponse(
        String outboxId,
        String taskId,
        String messageKey,
        int attemptCount,
        String lastError,
        Instant deadLetteredAt
) {
}
