package com.noteweave.personal.card.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

@Builder
public record ConceptCardResponse(
        Long id,
        Long spaceId,
        Long researchProjectId,
        String name,
        String normalizedName,
        String definition,
        String explanation,
        List<String> useCases,
        List<String> commonMisunderstandings,
        List<java.util.Map<String, Object>> evidenceQuotes,
        double confidence,
        List<String> aliases,
        List<CardCitationResponse> citations,
        List<ConceptRelationResponse> relations,
        List<RelatedArticleResponse> relatedArticles,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
