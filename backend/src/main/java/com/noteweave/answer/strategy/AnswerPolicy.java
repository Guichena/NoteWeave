package com.noteweave.answer.strategy;

public record AnswerPolicy(
        int minimumEvidence,
        boolean allowNoEvidenceAnswer,
        boolean citationRequired,
        int maximumOutputTokens
) {
}
