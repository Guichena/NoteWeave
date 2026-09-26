package com.noteweave.artifact;

public interface ArtifactWorkerExportClient {
    byte[] fetch(String taskId, String fileName);
}
