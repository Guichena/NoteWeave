package com.noteweave.artifact.service;

import com.noteweave.storage.config.StorageProperties;
import com.noteweave.storage.service.FileStorageService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ArtifactStorageSupport {

    private final FileStorageService fileStorageService;
    private final StorageProperties storageProperties;

    public String writeMarkdownExport(Long artifactId, String fileName, String content) {
        String objectKey = resolveObjectPrefix() + "/artifacts/" + artifactId + "/exports/" + safeFileName(fileName);
        byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        fileStorageService.putObject(currentBucket(), objectKey, new ByteArrayInputStream(bytes), bytes.length, "text/markdown; charset=utf-8");
        return objectKey;
    }

    public String currentBucket() {
        if (normalizeTestRunId(storageProperties.paths().testRunId()) != null) {
            return fileStorageService.testBucket();
        }
        return fileStorageService.devBucket();
    }

    private String resolveObjectPrefix() {
        String configuredTestRunId = normalizeTestRunId(storageProperties.paths().testRunId());
        if (configuredTestRunId != null) {
            return storageProperties.paths().testObjectPrefix() + "/" + configuredTestRunId;
        }
        return storageProperties.paths().devObjectPrefix();
    }

    private String normalizeTestRunId(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String safeFileName(String fileName) {
        String fallback = "artifact.md";
        if (fileName == null || fileName.isBlank()) {
            return fallback;
        }
        String sanitized = fileName.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
        return sanitized.isBlank() ? fallback : sanitized;
    }
}
