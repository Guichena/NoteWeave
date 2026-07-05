package com.noteweave.knowledge;

import java.util.List;
import java.util.Map;

public record WikiIndexResponse(
        String workspaceId,
        boolean wikiEnabled,
        int readySourceCount,
        int pageCount,
        int sourceBackedPageCount,
        int manualPageCount,
        int linkCount,
        int resolvedLinkCount,
        int unresolvedLinkCount,
        int citationCount,
        int issueCount,
        int autoFixableIssueCount,
        int manualReviewIssueCount,
        int pendingTaskCount,
        Map<String, Integer> pagesByKind,
        List<KnowledgeItemResponse> recentUpdates,
        List<WikiTaskSummaryResponse> recentTasks,
        List<WikiIndexSourceResponse> recentSources,
        List<WikiIssueResponse> topIssues
) {
}
