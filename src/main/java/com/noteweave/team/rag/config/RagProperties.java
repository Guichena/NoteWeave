package com.noteweave.team.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "noteweave.rag")
public record RagProperties(
        Retrieval retrieval,
        Prompt prompt
) {
    public record Retrieval(
            int topK,
            int perDocumentLimit,
            int contextMaxChars,
            int maxMergedChars
    ) {
    }

    public record Prompt(
            String noResultText
    ) {
    }
}
