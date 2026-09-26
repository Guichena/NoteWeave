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
        Instant createdAt,
        String fileRole,
        String variant,
        int sequenceNo
) {
    public ArtifactFileMetadataResponse(String fileId, String fileFormat, String fileName,
                                        String mediaType, String storageBackend, String bucketName,
                                        String objectKey, long sizeBytes, String checksumSha256,
                                        String status, String errorMessage, Instant createdAt) {
        this(fileId, fileFormat, fileName, mediaType, storageBackend, bucketName, objectKey,
                sizeBytes, checksumSha256, status, errorMessage, createdAt,
                "MARKDOWN".equals(fileFormat) ? "PRIMARY_MARKDOWN" :
                        "PDF".equals(fileFormat) ? "PRIMARY_PDF" : "LEGACY_" + fileFormat, "", 0);
    }
}
