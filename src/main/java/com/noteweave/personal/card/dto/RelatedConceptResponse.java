package com.noteweave.personal.card.dto;

import lombok.Builder;

@Builder
public record RelatedConceptResponse(
        Long conceptCardId,
        String name,
        double relevanceScore,
        String evidence
) {
}
