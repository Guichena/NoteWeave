package com.noteweave.knowledge;

import java.time.Instant;
import java.util.List;

public record KnowledgeVersionDetailResponse(
        String versionId,
        String itemId,
        int versionNo,
        String content,
        String summary,
        String sourceMessageId,
        List<KnowledgeCitationResponse> citations,
        Instant createdAt
) {
}
