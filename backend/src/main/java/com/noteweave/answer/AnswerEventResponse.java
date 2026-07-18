package com.noteweave.answer;

import java.time.Instant;

public record AnswerEventResponse(
        long sequence,
        String eventType,
        String payloadJson,
        Instant occurredAt
) {
}
