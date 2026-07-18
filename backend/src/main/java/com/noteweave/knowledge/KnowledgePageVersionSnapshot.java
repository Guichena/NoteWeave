package com.noteweave.knowledge;

import java.time.Instant;
import java.util.List;

public record KnowledgePageVersionSnapshot(
        String itemId,
        String versionId,
        int versionNo,
        String content,
        String summary,
        String sourceMessageId,
        List<KnowledgeCitationResponse> citations,
        Instant createdAt
) {
    public KnowledgePageVersionSnapshot {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
