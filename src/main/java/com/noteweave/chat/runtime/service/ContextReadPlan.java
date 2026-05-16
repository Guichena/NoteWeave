package com.noteweave.chat.runtime.service;

import lombok.Builder;

@Builder
public record ContextReadPlan(
        boolean readRecentHistory,
        boolean readRetrievalEvidence,
        boolean readLongTermMemory
) {
}
