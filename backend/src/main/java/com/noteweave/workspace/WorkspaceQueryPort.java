package com.noteweave.workspace;

public interface WorkspaceQueryPort {

    boolean exists(String workspaceId);

    boolean isWikiEnabled(String workspaceId);

    boolean isRetrievalStrategyV2Enabled(String workspaceId);
}
