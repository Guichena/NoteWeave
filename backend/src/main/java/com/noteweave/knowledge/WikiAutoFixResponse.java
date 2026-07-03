package com.noteweave.knowledge;

public record WikiAutoFixResponse(String workspaceId, int createdPages, int rebuiltLinks, int remainingIssues) {
}
