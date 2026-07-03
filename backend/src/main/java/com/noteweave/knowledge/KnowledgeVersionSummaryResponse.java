package com.noteweave.knowledge;

import java.time.Instant;

public record KnowledgeVersionSummaryResponse(
        String versionId,
        int versionNo,
        String summary,
        String sourceMessageId,
        int citationCount,
        Instant createdAt
) {
}
