package com.noteweave.answer;

import java.time.Instant;

public record AnswerLiveEvent(
        long sequence,
        String eventType,
        String data,
        Instant occurredAt
) {
}
