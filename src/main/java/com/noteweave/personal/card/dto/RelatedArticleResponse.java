package com.noteweave.personal.card.dto;

import lombok.Builder;

@Builder
public record RelatedArticleResponse(
        Long articleCardId,
        Long sourceId,
        String title,
        double relevanceScore,
        String evidence
) {
}
