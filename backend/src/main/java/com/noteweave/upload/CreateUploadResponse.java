package com.noteweave.upload;

public record CreateUploadResponse(String uploadId, String status, int chunkSize, int totalChunks,
        boolean instant, String sourceId, String taskId, String parseStatus, String indexStatus) {
    public CreateUploadResponse(String uploadId, String status, int chunkSize, int totalChunks) {
        this(uploadId, status, chunkSize, totalChunks, false, "", "", "", "");
    }
}
