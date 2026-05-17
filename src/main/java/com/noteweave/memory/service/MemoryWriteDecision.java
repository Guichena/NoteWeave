package com.noteweave.memory.service;

import java.math.BigDecimal;
import lombok.Builder;

@Builder
public record MemoryWriteDecision(
        boolean writeSessionSummary,
        boolean writeSpaceMemory,
        boolean writeUserMemory,
        String topic,
        String userPreferenceSummary,
        String spaceSummary,
        BigDecimal importanceScore,
        BigDecimal confidenceScore,
        String reason
) {
}
