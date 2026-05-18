package com.noteweave.admin.service;

import com.noteweave.storage.config.StorageProperties;
import com.noteweave.storage.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminStorageSupport {

    private final StorageProperties storageProperties;
    private final FileStorageService fileStorageService;

    public String currentBucket() {
        return currentTestRunId() == null ? fileStorageService.devBucket() : fileStorageService.testBucket();
    }

    public String currentObjectPrefix() {
        return currentTestRunId() == null
                ? storageProperties.paths().devObjectPrefix()
                : storageProperties.paths().testObjectPrefix() + "/" + currentTestRunId();
    }

    private String currentTestRunId() {
        String raw = storageProperties.paths().testRunId();
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
