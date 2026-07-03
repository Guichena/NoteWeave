package com.noteweave.upload;

public record UploadChunkResponse(String uploadId, int chunkIndex, boolean accepted) {
}
