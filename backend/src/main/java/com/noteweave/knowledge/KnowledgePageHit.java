package com.noteweave.knowledge;

import com.noteweave.answer.strategy.ScoreInfo;

public record KnowledgePageHit(
        String itemId,
        String versionId,
        int versionNo,
        String title,
        String content,
        String summary,
        int score,
        ScoreInfo scoreInfo
) {
    public KnowledgePageHit { scoreInfo = scoreInfo == null ? ScoreInfo.unknown() : scoreInfo; }
    public KnowledgePageHit(String itemId, String versionId, int versionNo, String title,
                            String content, String summary, int score) {
        this(itemId, versionId, versionNo, title, content, summary, score, ScoreInfo.unknown());
    }
}
