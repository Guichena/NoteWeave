package com.noteweave.personal.card.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.Builder;

@Builder
public record SynthesisCardResponse(
        Long id,
        Long spaceId,
        Long researchProjectId,
        Long sourceArtifactId,
        Long sourceArtifactVersionId,
        String title,
        String summary,
        List<String> insights,
        List<Map<String, Object>> evidenceQuotes,
        String cardStatus,
        Long createdBy,
        List<CardCitationResponse> citations,
        List<SynthesisConceptRelationResponse> conceptRelations,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
