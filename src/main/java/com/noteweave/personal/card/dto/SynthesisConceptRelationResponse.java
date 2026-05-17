package com.noteweave.personal.card.dto;

import lombok.Builder;

@Builder
public record SynthesisConceptRelationResponse(
        Long conceptCardId,
        String name,
        String relationType,
        String evidence
) {
}
