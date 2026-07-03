package com.noteweave.task;

import java.time.Instant;

public record TaskEventResponse(
        String eventId,
        String eventType,
        String message,
        Instant createdAt
) {
}
