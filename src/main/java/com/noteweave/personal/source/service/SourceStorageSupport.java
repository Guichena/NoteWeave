package com.noteweave.personal.source.service;

import com.noteweave.storage.config.StorageProperties;
import com.noteweave.storage.service.FileStorageService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SourceStorageSupport {

    private final FileStorageService fileStorageService;
    private final StorageProperties storageProperties;

    public String currentBucket() {
        if (normalizeTestRunId(storageProperties.paths().testRunId()) != null) {
            return fileStorageService.testBucket();
        }
        return fileStorageService.devBucket();
    }

    public String buildFileObjectKey(String contentHash, String fileName) {
        return resolveObjectPrefix() + "/source-files/" + contentHash + "/" + safeFileName(fileName);
    }

    public String rawTextObjectKey(Long sourceId, int attempt) {
        return resolveObjectPrefix() + "/raw-text/source/" + sourceId + "/" + attempt + ".txt";
    }

    public String parsedTextObjectKey(Long sourceId, int attempt) {
        return resolveObjectPrefix() + "/parsed-text/source/" + sourceId + "/" + attempt + ".txt";
    }

    public void putOriginalObject(String objectKey, byte[] bytes, String contentType) {
        fileStorageService.putObject(
                currentBucket(),
                objectKey,
                new ByteArrayInputStream(bytes),
                bytes.length,
                contentType
        );
    }

    public void writeTextObject(String objectKey, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        fileStorageService.putObject(
                currentBucket(),
                objectKey,
                new ByteArrayInputStream(bytes),
                bytes.length,
                "text/plain; charset=utf-8"
        );
    }

    public boolean objectExists(String objectKey) {
        return objectKey != null && fileStorageService.objectExists(currentBucket(), objectKey);
    }

    public InputStream getObject(String objectKey) {
        return fileStorageService.getObject(currentBucket(), objectKey);
    }

    public String readTextObject(String objectKey) {
        try (InputStream inputStream = getObject(objectKey)) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read text object: " + objectKey, ex);
        }
    }

    public String fileNameFromObjectKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        int index = objectKey.lastIndexOf('/');
        if (index < 0 || index == objectKey.length() - 1) {
            return objectKey;
        }
        return objectKey.substring(index + 1);
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
        String fallback = "source.bin";
        if (fileName == null || fileName.isBlank()) {
            return fallback;
        }
        String sanitized = fileName.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
        return sanitized.isBlank() ? fallback : sanitized;
    }
}
