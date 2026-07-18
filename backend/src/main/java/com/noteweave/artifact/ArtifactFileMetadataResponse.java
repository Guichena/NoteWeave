package com.noteweave.artifact;

import java.time.Instant;

public record ArtifactFileMetadataResponse(
        String fileId,
        String fileFormat,
        String fileName,
        String mediaType,
        String storageBackend,
        String bucketName,
        String objectKey,
        long sizeBytes,
        String checksumSha256,
        String status,
        String errorMessage,
        Instant createdAt
) {
}
