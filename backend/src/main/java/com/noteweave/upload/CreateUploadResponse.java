package com.noteweave.upload;

public record CreateUploadResponse(String uploadId, String status, int chunkSize, int totalChunks) {
}
