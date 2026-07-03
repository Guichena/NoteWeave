package com.noteweave.chat;

public record CitationItem(
        String citationId,
        String sourceId,
        String title,
        String quoteText,
        Integer pageNo,
        String locationInfo
) {
}
