package com.noteweave.team.document.service;

import com.noteweave.storage.config.StorageProperties;
import com.noteweave.storage.service.FileStorageService;
import com.noteweave.team.document.model.DocumentUpload;
import com.noteweave.team.document.model.DocumentUploadStatus;
import com.noteweave.team.document.model.UploadChunk;
import com.noteweave.team.document.repository.DocumentUploadRepository;
import com.noteweave.team.document.repository.UploadChunkRepository;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UploadCleanupService {

    private static final EnumSet<DocumentUploadStatus> EXPIRABLE_STATUSES =
            EnumSet.of(DocumentUploadStatus.INIT, DocumentUploadStatus.UPLOADING, DocumentUploadStatus.FAILED);

    private final DocumentUploadRepository documentUploadRepository;
    private final UploadChunkRepository uploadChunkRepository;
    private final UploadBitmapService uploadBitmapService;
    private final FileStorageService fileStorageService;
    private final StorageProperties storageProperties;

    @Transactional
    public int cleanupExpiredUploads(LocalDateTime now) {
        List<DocumentUpload> candidates = documentUploadRepository.findByExpiresAtBeforeAndStatusIn(now, EXPIRABLE_STATUSES);
        int cleaned = 0;
        for (DocumentUpload candidate : candidates) {
            DocumentUpload upload = documentUploadRepository.findByIdForUpdate(candidate.getId()).orElse(null);
            if (upload == null || !shouldExpire(upload, now)) {
                continue;
            }
            upload.setStatus(DocumentUploadStatus.EXPIRED);
            documentUploadRepository.save(upload);
            cleanupTemporaryResources(upload.getId());
            cleaned++;
        }
        return cleaned;
    }

    @Transactional
    public void cleanupUpload(Long uploadId) {
        DocumentUpload upload = documentUploadRepository.findByIdForUpdate(uploadId).orElse(null);
        if (upload == null) {
            return;
        }
        if (EXPIRABLE_STATUSES.contains(upload.getStatus())) {
            upload.setStatus(DocumentUploadStatus.CANCELLED);
            upload.setCancelledAt(LocalDateTime.now());
            documentUploadRepository.save(upload);
        }
        cleanupTemporaryResources(uploadId);
    }

    private boolean shouldExpire(DocumentUpload upload, LocalDateTime now) {
        return upload.getExpiresAt() != null
                && upload.getExpiresAt().isBefore(now)
                && EXPIRABLE_STATUSES.contains(upload.getStatus());
    }

    private void cleanupTemporaryResources(Long uploadId) {
        List<UploadChunk> chunks = uploadChunkRepository.findByUploadIdOrderByChunkIndexAsc(uploadId);
        String chunkPathMark = "/uploads/" + uploadId + "/chunks/";
        String bucket = currentBucket();
        for (UploadChunk chunk : chunks) {
            String objectKey = chunk.getObjectKey();
            if (objectKey == null || !objectKey.contains(chunkPathMark)) {
                continue;
            }
            if (fileStorageService.objectExists(bucket, objectKey)) {
                fileStorageService.removeObject(bucket, objectKey);
            }
        }
        uploadChunkRepository.deleteByUploadId(uploadId);
        uploadBitmapService.clear(uploadId);
    }

    private String currentBucket() {
        String raw = storageProperties.paths().testRunId();
        if (raw != null && !raw.trim().isEmpty()) {
            return fileStorageService.testBucket();
        }
        return fileStorageService.devBucket();
    }
}
