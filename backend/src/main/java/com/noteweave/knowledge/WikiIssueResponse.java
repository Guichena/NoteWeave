package com.noteweave.knowledge;

public record WikiIssueResponse(
        String issueType,
        String severity,
        String itemId,
        String title,
        String message,
        String suggestedAction,
        boolean autoFixable,
        String actionCode
) {
}
