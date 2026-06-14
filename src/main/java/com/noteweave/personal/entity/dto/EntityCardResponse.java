package com.noteweave.personal.entity.dto;

import com.noteweave.personal.card.model.PersonalCardStatus;
import com.noteweave.personal.entity.model.EntityType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

@Builder
public record EntityCardResponse(
        Long id,
        Long spaceId,
        Long researchProjectId,
        String canonicalName,
        String normalizedName,
        EntityType entityType,
        List<String> aliases,
        String description,
        List<String> externalRefs,
        BigDecimal confidence,
        PersonalCardStatus cardStatus,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
