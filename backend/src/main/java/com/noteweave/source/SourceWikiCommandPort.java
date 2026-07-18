package com.noteweave.source;

public interface SourceWikiCommandPort {

    void requestSourceIngest(String workspaceId, String sourceId);

    String requestSourceRetract(String workspaceId, String sourceId, String sourceTitle);
}
