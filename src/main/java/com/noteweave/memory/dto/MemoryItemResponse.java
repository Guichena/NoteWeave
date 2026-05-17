package com.noteweave.memory.dto;

import com.noteweave.memory.model.MemoryType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;

@Builder
public record MemoryItemResponse(
        Long id,
        MemoryType memoryType,
        String topic,
        String summary,
        String sourceType,
        Long sourceId,
        BigDecimal importanceScore,
        BigDecimal confidenceScore,
        boolean pin,
        LocalDateTime expiresAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
