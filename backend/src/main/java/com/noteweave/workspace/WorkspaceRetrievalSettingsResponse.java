package com.noteweave.workspace;

public record WorkspaceRetrievalSettingsResponse(
        String workspaceId,
        boolean retrievalStrategyV2Enabled
) {
}
