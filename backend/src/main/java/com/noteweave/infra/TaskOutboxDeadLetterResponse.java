package com.noteweave.infra;

import java.time.Instant;

public record TaskOutboxDeadLetterResponse(
        String outboxId, String taskId, String topic, String messageKey,
        int attemptCount, String lastError, Instant deadLetteredAt
) {
}
