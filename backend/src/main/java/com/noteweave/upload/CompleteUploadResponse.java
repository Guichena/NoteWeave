package com.noteweave.upload;

public record CompleteUploadResponse(String sourceId, String taskId, String parseStatus, String indexStatus) {
}
