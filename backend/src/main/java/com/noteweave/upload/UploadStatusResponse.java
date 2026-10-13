package com.noteweave.upload;

import java.util.List;

public record UploadStatusResponse(String uploadId, String status, int totalChunks, int chunkSize,
        List<Integer> uploadedChunkIndexes) {}
