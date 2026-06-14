package com.noteweave.chat.runtime.service;

import lombok.Builder;

@Builder
public record ContextReadPlan(
        boolean readRecentHistory,
        boolean readQuestionMemory,
        boolean readSessionSummary,
        boolean readSpaceMemory,
        boolean readUserMemory,
        boolean readRetrievalEvidence,
        boolean readLongTermMemory
) {
}
