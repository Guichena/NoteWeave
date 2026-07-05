package com.noteweave.knowledge;

import java.util.List;
import java.util.Map;

public record WikiStatsResponse(
        String workspaceId,
        int pageCount,
        int linkCount,
        int resolvedLinkCount,
        int unresolvedLinkCount,
        int citationCount,
        int issueCount,
        int autoFixableIssueCount,
        int manualReviewIssueCount,
        Map<String, Integer> pagesByKind,
        List<KnowledgeItemResponse> recentUpdates,
        List<WikiTaskSummaryResponse> recentTasks,
        int pendingTaskCount,
        boolean wikiEnabled
) {
}
