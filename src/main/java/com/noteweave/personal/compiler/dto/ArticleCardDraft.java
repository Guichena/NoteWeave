package com.noteweave.personal.compiler.dto;

import java.util.List;

public record ArticleCardDraft(
        String title,
        String summary,
        List<String> keyPoints,
        List<String> tags,
        List<EvidenceQuoteDraft> evidenceQuotes
) {
}
