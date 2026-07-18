package com.noteweave.knowledge;

public record KnowledgePageHit(
        String itemId,
        String versionId,
        int versionNo,
        String title,
        String content,
        String summary,
        int score
) {
}
