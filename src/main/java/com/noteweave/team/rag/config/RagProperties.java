package com.noteweave.team.rag.config;

import com.noteweave.team.rag.retriever.RetrievalMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "noteweave.rag")
public record RagProperties(
        Retrieval retrieval,
        Prompt prompt
) {
    public record Retrieval(
            RetrievalMode mode,
            int topK,
            int perDocumentLimit,
            int contextMaxChars,
            int maxMergedChars,
            double minScore,
            double bm25Weight,
            double vectorWeight,
            double wikiWeight,
            double claimWeight,
            int rrfK
    ) {
    }

    public record Prompt(
            String noResultText
    ) {
    }
}
