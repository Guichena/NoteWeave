package com.noteweave.memory;

public record AnswerFeedbackResponse(
        String answerRunId,
        String rating,
        String aspect,
        String degradationReason,
        int affectedMemoryCount
) {
}
