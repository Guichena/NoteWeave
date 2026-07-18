package com.noteweave.source;

public interface SourceParsePort {

    void parseAndIndex(String workspaceId, String sourceId, String snapshotId, byte[] bytes);

    void parseAndIndexAsync(String workspaceId, String sourceId, String snapshotId);
}
