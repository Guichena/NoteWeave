package com.noteweave.knowledge;

public record KnowledgeCitationResponse(
        String citationId,
        String sourceId,
        String title,
        String quoteText,
        Integer pageNo,
        String locationInfo
) {
}
