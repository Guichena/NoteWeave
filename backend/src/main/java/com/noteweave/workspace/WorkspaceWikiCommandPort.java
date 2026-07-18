package com.noteweave.workspace;

public interface WorkspaceWikiCommandPort {

    void requestWorkspaceBackfill(String workspaceId, String reason);
}
