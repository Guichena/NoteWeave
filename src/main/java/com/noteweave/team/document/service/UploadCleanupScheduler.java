package com.noteweave.team.document.service;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class UploadCleanupScheduler {

    private final UploadCleanupService uploadCleanupService;

    @Value("${noteweave.upload.cleanup.enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${noteweave.upload.cleanup.fixed-delay-ms:600000}")
    public void cleanupExpiredUploads() {
        if (!enabled) {
            return;
        }
        try {
            uploadCleanupService.cleanupExpiredUploads(LocalDateTime.now());
        } catch (Exception ex) {
            log.warn("Scheduled expired upload cleanup failed", ex);
        }
    }
}
