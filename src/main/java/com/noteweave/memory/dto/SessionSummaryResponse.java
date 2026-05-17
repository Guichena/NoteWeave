package com.noteweave.memory.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;

@Builder
public record SessionSummaryResponse(
        Long id,
        Long sessionId,
        String topic,
        String summary,
        BigDecimal importanceScore,
        BigDecimal confidenceScore,
        boolean pin,
        LocalDateTime expiresAt,
        LocalDateTime createdAt
) {
}
