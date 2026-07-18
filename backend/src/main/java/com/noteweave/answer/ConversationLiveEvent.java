package com.noteweave.answer;

import java.time.Instant;

/** A conversation-wide cursor wrapping an event from one AnswerRun. */
public record ConversationLiveEvent(
        long sequence,
        String runId,
        long runSequence,
        String eventType,
        String data,
        Instant occurredAt
) {
}
