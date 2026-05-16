package com.noteweave.personal.card.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

@Builder
public record ArticleCardResponse(
        Long id,
        Long spaceId,
        Long researchProjectId,
        Long sourceId,
        String title,
        String summary,
        List<String> keyPoints,
        List<String> tags,
        List<java.util.Map<String, Object>> evidenceQuotes,
        List<CardCitationResponse> citations,
        List<RelatedConceptResponse> relatedConcepts,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
