package com.noteweave.personal.card.dto;

import lombok.Builder;

@Builder
public record ConceptRelationResponse(
        Long conceptCardId,
        String name,
        String relationType,
        String description
) {
}
