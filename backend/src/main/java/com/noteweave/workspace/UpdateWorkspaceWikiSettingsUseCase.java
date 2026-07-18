package com.noteweave.workspace;

import org.springframework.stereotype.Service;

@Service
public class UpdateWorkspaceWikiSettingsUseCase {

    private final WorkspaceService workspaceService;
    private final WorkspaceWikiCommandPort wikiCommandPort;

    public UpdateWorkspaceWikiSettingsUseCase(
            WorkspaceService workspaceService,
            WorkspaceWikiCommandPort wikiCommandPort
    ) {
        this.workspaceService = workspaceService;
        this.wikiCommandPort = wikiCommandPort;
    }

    public WorkspaceWikiSettingsResponse execute(
            String workspaceId,
            UpdateWorkspaceWikiSettingsRequest request
    ) {
        boolean wasEnabled = workspaceService.isWikiEnabled(workspaceId);
        WorkspaceWikiSettingsResponse response = workspaceService.updateWikiSettings(workspaceId, request);
        if (!wasEnabled && request.wikiEnabled()) {
            wikiCommandPort.requestWorkspaceBackfill(workspaceId, "enable_backfill");
        }
        return response;
    }
}
