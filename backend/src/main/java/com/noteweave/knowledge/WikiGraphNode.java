package com.noteweave.knowledge;

public record WikiGraphNode(
        String itemId,
        String title,
        String pageKind,
        int versionNo,
        int degree,
        int outgoingCount,
        int backlinkCount,
        int citationCount,
        int unresolvedCount
) {
}
