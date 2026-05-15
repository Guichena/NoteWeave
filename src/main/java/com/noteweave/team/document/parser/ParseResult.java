package com.noteweave.team.document.parser;

import java.util.Map;

public record ParseResult(
        String text,
        String detectedContentType,
        long characterCount,
        Map<String, Object> metadata
) {
}
