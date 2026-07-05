package com.noteweave.knowledge;

public record WikiRebuildAdviceResponse(
        boolean shouldEnableWiki,
        int readySourceCount,
        int activeWikiPageCount,
        String message,
        String recommendedAction,
        String recommendedIssueType,
        String focusItemId,
        String focusTitle
) {
}
