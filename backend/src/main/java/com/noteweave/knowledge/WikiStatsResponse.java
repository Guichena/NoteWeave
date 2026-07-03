package com.noteweave.knowledge;

public record WikiStatsResponse(
        String workspaceId,
        int pageCount,
        int linkCount,
        int resolvedLinkCount,
        int unresolvedLinkCount,
        int citationCount,
        int issueCount
) {
}
