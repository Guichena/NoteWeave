package com.noteweave.personal.compiler.dto;

import java.util.Map;

public record EvidenceQuoteDraft(
        String quote,
        Long sourceId,
        String reason,
        Map<String, Object> extras
) {
}
