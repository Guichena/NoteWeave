package com.noteweave.storage.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "noteweave.storage")
public record StorageProperties(
        Minio minio,
        Paths paths
) {
    public record Minio(
            @NotBlank String endpoint,
            @NotBlank String accessKey,
            @NotBlank String secretKey,
            @NotBlank String bucket,
            @NotBlank String testBucket
    ) {
    }

    public record Paths(
            @NotBlank String localTestRoot,
            @NotBlank String devObjectPrefix,
            @NotBlank String testObjectPrefix,
            String testRunId
    ) {
    }
}
