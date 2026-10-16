package com.noteweave.knowledge;

/** 一条已解析的 Wiki 链接。PageRank 会再补上反方向。 */
public record WikiResolvedLink(
        String sourceItemId,
        String targetItemId,
        String sourceTitle,
        String targetTitle,
        int mentionCount
) {
}
