package com.noteweave.team.document.service;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.permission.service.ResourceAccessService;
import com.noteweave.search.service.SearchIndexService;
import com.noteweave.storage.config.StorageProperties;
import com.noteweave.storage.service.FileStorageService;
import com.noteweave.task.dto.TaskResponse;
import com.noteweave.task.model.TaskType;
import com.noteweave.task.service.TaskCreateCommand;
import com.noteweave.task.service.TaskService;
import com.noteweave.team.document.dto.DocumentProcessTaskPayload;
import com.noteweave.team.document.dto.DocumentChunkResponse;
import com.noteweave.team.document.dto.DocumentResponse;
import com.noteweave.team.document.dto.InitUploadRequest;
import com.noteweave.team.document.dto.InitUploadResponse;
import com.noteweave.team.document.dto.MergeUploadResponse;
import com.noteweave.team.document.dto.UploadChunkResponse;
import com.noteweave.team.document.dto.UploadStatusResponse;
import com.noteweave.team.document.model.Document;
import com.noteweave.team.document.model.DocumentStatus;
import com.noteweave.team.document.model.DocumentUpload;
import com.noteweave.team.document.model.DocumentUploadStatus;
import com.noteweave.team.document.model.FileObject;
import com.noteweave.team.document.model.FileObjectStatus;
import com.noteweave.team.document.model.UploadChunk;
import com.noteweave.team.document.repository.DocumentRepository;
import com.noteweave.team.document.repository.DocumentUploadRepository;
import com.noteweave.team.document.repository.FileObjectRepository;
import com.noteweave.team.document.repository.UploadChunkRepository;
import com.noteweave.team.kb.model.KnowledgeBase;
import com.noteweave.team.kb.service.KnowledgeBaseService;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class DocumentUploadService {

    private static final String DOCUMENT_TARGET_TYPE = "DOCUMENT";
    private static final String DOCUMENT_SOURCE_TYPE = "FILE_UPLOAD";
    private static final Set<DocumentUploadStatus> INSTANT_REUSABLE_UPLOAD_STATUSES =
            Set.of(DocumentUploadStatus.MERGED, DocumentUploadStatus.PROCESSING);
    private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of(
            "text/plain",
            "text/markdown",
            "text/x-markdown",
            "application/pdf"
    );

    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentUploadRepository documentUploadRepository;
    private final UploadChunkRepository uploadChunkRepository;
    private final FileObjectRepository fileObjectRepository;
    private final DocumentRepository documentRepository;
    private final ResourceAccessService resourceAccessService;
    private final UploadBitmapService uploadBitmapService;
    private final FileStorageService fileStorageService;
    private final StorageProperties storageProperties;
    private final TaskService taskService;
    private final DocumentChunkService documentChunkService;
    private final SearchIndexService searchIndexService;

    @Value("${noteweave.upload.bitmap-ttl-hours:24}")
    private long bitmapTtlHours;

    @Value("${noteweave.kafka.topics.document-process:noteweave.document}")
    private String documentTopic;

    @Transactional
    public InitUploadResponse initUpload(Long userId, Long knowledgeBaseId, InitUploadRequest request) {
        KnowledgeBase kb = knowledgeBaseService.getRequiredActiveKb(knowledgeBaseId);
        resourceAccessService.requireUploadDocument(userId, kb.getSpaceId());
        validateInit(request);

        String normalizedFileMd5 = request.getFileMd5().toLowerCase();
        DocumentUpload upload = new DocumentUpload();
        upload.setSpaceId(kb.getSpaceId());
        upload.setKnowledgeBaseId(kb.getId());
        upload.setUserId(userId);
        upload.setFileMd5(normalizedFileMd5);
        upload.setFileName(request.getFileName().trim());
        upload.setContentType(normalizeContentType(request.getContentType()));
        upload.setTotalSize(request.getTotalSize());
        upload.setChunkSize(request.getChunkSize());
        upload.setTotalChunks(request.getTotalChunks());
        upload.setStatus(DocumentUploadStatus.UPLOADING);
        upload.setExpiresAt(LocalDateTime.now().plusHours(Math.max(bitmapTtlHours, 1L)));
        upload = documentUploadRepository.save(upload);

        boolean instantUpload = tryInstantReuse(userId, kb, upload, normalizedFileMd5);

        return InitUploadResponse.builder()
                .uploadId(upload.getId())
                .documentId(upload.getDocumentId())
                .fileMd5(upload.getFileMd5())
                .uploadedChunks(List.of())
                .totalChunks(upload.getTotalChunks())
                .instantUpload(instantUpload)
                .status(upload.getStatus())
                .build();
    }

    @Transactional
    public UploadChunkResponse uploadChunk(Long userId, Long uploadId, Integer chunkIndex, MultipartFile file) {
        DocumentUpload upload = loadUploadForWrite(uploadId);
        requireUploadPermission(userId, upload);
        validateChunk(upload, chunkIndex, file);

        if (uploadBitmapService.isChunkUploaded(uploadId, chunkIndex)) {
            List<Integer> uploaded = uploadBitmapService.getUploadedChunks(uploadId, upload.getTotalChunks());
            return chunkResponse(upload, chunkIndex, uploaded, true);
        }

        if (uploadChunkRepository.findByUploadIdAndChunkIndex(uploadId, chunkIndex).isPresent()) {
            uploadBitmapService.markChunkUploaded(uploadId, chunkIndex, Duration.ofHours(Math.max(bitmapTtlHours, 1L)));
            List<Integer> uploaded = uploadBitmapService.getUploadedChunks(uploadId, upload.getTotalChunks());
            return chunkResponse(upload, chunkIndex, uploaded, true);
        }

        String chunkKey = buildChunkObjectKey(uploadId, chunkIndex);
        String chunkMd5 = md5Hex(file);
        try (InputStream in = file.getInputStream()) {
            fileStorageService.putObject(currentBucket(), chunkKey, in, file.getSize(), normalizeContentType(upload.getContentType()));
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.STORAGE_OPERATION_FAILED, "failed to persist chunk");
        }

        UploadChunk chunk = new UploadChunk();
        chunk.setUploadId(uploadId);
        chunk.setFileMd5(upload.getFileMd5());
        chunk.setChunkIndex(chunkIndex);
        chunk.setChunkMd5(chunkMd5);
        chunk.setSize(file.getSize());
        chunk.setObjectKey(chunkKey);
        uploadChunkRepository.save(chunk);

        upload.setStatus(DocumentUploadStatus.UPLOADING);
        documentUploadRepository.save(upload);

        uploadBitmapService.markChunkUploaded(uploadId, chunkIndex, Duration.ofHours(Math.max(bitmapTtlHours, 1L)));
        List<Integer> uploaded = uploadBitmapService.getUploadedChunks(uploadId, upload.getTotalChunks());
        return chunkResponse(upload, chunkIndex, uploaded, true);
    }

    @Transactional(readOnly = true)
    public UploadStatusResponse getStatus(Long userId, Long uploadId) {
        DocumentUpload upload = documentUploadRepository.findByIdAndStatusNot(uploadId, DocumentUploadStatus.DELETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.UPLOAD_NOT_FOUND));
        requireUploadViewPermission(userId, upload);
        List<Integer> uploaded = completedUploadStatuses().contains(upload.getStatus())
                ? allChunks(upload.getTotalChunks())
                : uploadBitmapService.getUploadedChunks(upload.getId(), upload.getTotalChunks());
        return UploadStatusResponse.builder()
                .uploadId(upload.getId())
                .fileMd5(upload.getFileMd5())
                .status(upload.getStatus())
                .uploadedChunks(uploaded)
                .totalChunks(upload.getTotalChunks())
                .progress(progress(uploaded.size(), upload.getTotalChunks()))
                .documentId(upload.getDocumentId())
                .taskId(upload.getTaskId())
                .build();
    }

    @Transactional
    public MergeUploadResponse merge(Long userId, Long uploadId) {
        DocumentUpload upload = loadUploadForWrite(uploadId);
        requireUploadPermission(userId, upload);

        if (upload.getDocumentId() != null
                && (upload.getStatus() == DocumentUploadStatus.MERGED || upload.getStatus() == DocumentUploadStatus.PROCESSING)) {
            return MergeUploadResponse.builder()
                    .uploadId(upload.getId())
                    .documentId(upload.getDocumentId())
                    .taskId(upload.getTaskId())
                    .status(upload.getStatus())
                    .objectKey(upload.getObjectKey())
                    .build();
        }

        List<UploadChunk> chunks = uploadChunkRepository.findByUploadIdOrderByChunkIndexAsc(uploadId);
        if (chunks.size() != upload.getTotalChunks()) {
            throw new BusinessException(ErrorCode.UPLOAD_CHUNK_INCOMPLETE);
        }
        for (int i = 0; i < upload.getTotalChunks(); i++) {
            if (!uploadBitmapService.isChunkUploaded(uploadId, i)) {
                throw new BusinessException(ErrorCode.UPLOAD_CHUNK_INCOMPLETE);
            }
            UploadChunk chunk = chunks.get(i);
            if (chunk.getChunkIndex() != i) {
                throw new BusinessException(ErrorCode.UPLOAD_CHUNK_INCOMPLETE);
            }
            if (!fileStorageService.objectExists(currentBucket(), chunk.getObjectKey())) {
                throw new BusinessException(ErrorCode.STORAGE_OBJECT_NOT_FOUND, "chunk object missing: " + chunk.getObjectKey());
            }
        }

        String contentHash = computeSha256FromChunks(chunks);
        String finalObjectKey = buildObjectKey(contentHash);
        boolean finalObjectExists = fileStorageService.objectExists(currentBucket(), finalObjectKey);
        if (!finalObjectExists) {
            mergeChunkObjects(upload, chunks, finalObjectKey);
        }

        FileObject fileObject = fileObjectRepository.findBySpaceIdAndContentHashForUpdate(upload.getSpaceId(), contentHash)
                .orElseGet(() -> {
                    FileObject created = new FileObject();
                    created.setSpaceId(upload.getSpaceId());
                    created.setContentHash(contentHash);
                    created.setObjectKey(finalObjectKey);
                    created.setSize(upload.getTotalSize());
                    created.setContentType(normalizeContentType(upload.getContentType()));
                    created.setStatus(FileObjectStatus.ACTIVE);
                    created.setRefCount(0);
                    return fileObjectRepository.save(created);
                });

        Document document = new Document();
        document.setSpaceId(upload.getSpaceId());
        document.setKnowledgeBaseId(upload.getKnowledgeBaseId());
        document.setFileObjectId(fileObject.getId());
        document.setTitle(upload.getFileName());
        document.setSourceType(DOCUMENT_SOURCE_TYPE);
        document.setObjectKey(finalObjectKey);
        document.setOriginalFilename(upload.getFileName());
        document.setContentHash(contentHash);
        document.setStatus(DocumentStatus.PENDING_PROCESS);
        document.setCreatedBy(userId);
        document = documentRepository.save(document);

        // refCount only increments after Document successfully binds this FileObject.
        fileObject.setRefCount(fileObject.getRefCount() + 1);
        fileObjectRepository.save(fileObject);

        Map<String, Object> input = new HashMap<>();
        input.put("documentId", document.getId());
        input.put("spaceId", document.getSpaceId());
        input.put("knowledgeBaseId", document.getKnowledgeBaseId());
        input.put("fileMd5", upload.getFileMd5());
        input.put("objectKey", finalObjectKey);
        input.put("fileName", upload.getFileName());
        input.put("contentType", normalizeContentType(upload.getContentType()));
        input.put("uploadedBy", upload.getUserId());
        input.put("createdAt", Instant.now().toString());

        TaskResponse task = taskService.createTask(TaskCreateCommand.builder()
                .userId(userId)
                .spaceId(upload.getSpaceId())
                .taskType(TaskType.DOCUMENT_PROCESS)
                .targetType(DOCUMENT_TARGET_TYPE)
                .targetId(document.getId())
                .idempotencyKey("DOCUMENT_PROCESS:" + document.getId() + ":" + upload.getFileMd5())
                .input(input)
                .build());

        upload.setDocumentId(document.getId());
        upload.setTaskId(task.getId());
        upload.setObjectKey(finalObjectKey);
        upload.setStatus(DocumentUploadStatus.MERGED);
        upload.setMergedAt(LocalDateTime.now());
        documentUploadRepository.save(upload);

        upload.setStatus(DocumentUploadStatus.PROCESSING);
        documentUploadRepository.save(upload);
        removeChunkObjects(chunks);
        uploadChunkRepository.deleteByUploadId(upload.getId());
        uploadBitmapService.clear(upload.getId());

        return MergeUploadResponse.builder()
                .uploadId(upload.getId())
                .documentId(document.getId())
                .taskId(task.getId())
                .status(upload.getStatus())
                .objectKey(upload.getObjectKey())
                .build();
    }

    @Transactional
    public void cancelUpload(Long userId, Long uploadId) {
        DocumentUpload upload = loadUploadForWrite(uploadId);
        requireUploadPermission(userId, upload);
        if (!EnumSet.of(DocumentUploadStatus.INIT, DocumentUploadStatus.UPLOADING, DocumentUploadStatus.FAILED)
                .contains(upload.getStatus())) {
            return;
        }
        upload.setStatus(DocumentUploadStatus.CANCELLED);
        upload.setCancelledAt(LocalDateTime.now());
        documentUploadRepository.save(upload);
        List<UploadChunk> chunks = uploadChunkRepository.findByUploadIdOrderByChunkIndexAsc(uploadId);
        removeChunkObjects(chunks);
        uploadChunkRepository.deleteByUploadId(uploadId);
        uploadBitmapService.clear(uploadId);
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> listDocuments(Long userId, Long knowledgeBaseId) {
        KnowledgeBase kb = knowledgeBaseService.getRequiredActiveKb(knowledgeBaseId);
        resourceAccessService.requireViewSpace(userId, kb.getSpaceId());
        return documentRepository.findByKnowledgeBaseIdAndDeletedAtIsNullAndStatusNotOrderByCreatedAtDesc(knowledgeBaseId, DocumentStatus.DELETED)
                .stream()
                .map(this::toDocumentResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public DocumentResponse getDocument(Long userId, Long documentId) {
        Document document = documentRepository.findByIdAndDeletedAtIsNullAndStatusNot(documentId, DocumentStatus.DELETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
        resourceAccessService.requireViewSpace(userId, document.getSpaceId());
        return toDocumentResponse(document);
    }

    @Transactional(readOnly = true)
    public List<DocumentChunkResponse> listDocumentChunks(Long userId, Long documentId) {
        Document document = documentRepository.findByIdAndDeletedAtIsNullAndStatusNot(documentId, DocumentStatus.DELETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
        resourceAccessService.requireViewSpace(userId, document.getSpaceId());
        return documentChunkService.listActiveChunks(document);
    }

    @Transactional
    public void deleteDocument(Long userId, Long documentId) {
        Document document = documentRepository.findByIdForUpdate(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
        resourceAccessService.requireUploadDocument(userId, document.getSpaceId());
        if (document.getDeletedAt() != null || document.getStatus() == DocumentStatus.DELETED) {
            return;
        }
        document.setStatus(DocumentStatus.DELETED);
        document.setDeletedAt(LocalDateTime.now());
        document.setDeletedBy(userId);
        documentRepository.save(document);
        searchIndexService.synchronizeDocumentChunkState(documentId, document.getStatus().name(), document.getActiveIndexVersion(), "DELETED");
        searchIndexService.deleteByDocumentId(documentId);
        // Phase 2 hard constraint: soft delete must not decrement refCount.
    }

    @Transactional
    public TaskResponse reindexDocument(Long userId, Long documentId) {
        Document document = documentRepository.findByIdAndDeletedAtIsNullAndStatusNot(documentId, DocumentStatus.DELETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
        resourceAccessService.requireUploadDocument(userId, document.getSpaceId());
        FileObject fileObject = fileObjectRepository.findById(document.getFileObjectId())
                .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_OBJECT_NOT_FOUND));
        String fileName = document.getOriginalFilename() == null || document.getOriginalFilename().isBlank()
                ? document.getTitle()
                : document.getOriginalFilename();
        String contentType = normalizeContentType(fileObject.getContentType());
        int nextIndexVersion = Math.max(document.getActiveIndexVersion(), 0) + 1;
        return taskService.createTask(TaskCreateCommand.builder()
                .userId(userId)
                .spaceId(document.getSpaceId())
                .taskType(TaskType.DOCUMENT_PROCESS)
                .targetType(DOCUMENT_TARGET_TYPE)
                .targetId(document.getId())
                .idempotencyKey("DOCUMENT_PROCESS:" + document.getId() + ":REINDEX:" + nextIndexVersion)
                .input(Map.of(
                        "documentId", document.getId(),
                        "spaceId", document.getSpaceId(),
                        "knowledgeBaseId", document.getKnowledgeBaseId(),
                        "objectKey", document.getObjectKey(),
                        "fileName", fileName,
                        "contentType", contentType,
                        "indexVersion", nextIndexVersion,
                        "reindex", true
                ))
                .build());
    }

    public String topic() {
        return documentTopic;
    }

    public DocumentProcessTaskPayload toDocumentProcessPayload(TaskResponse task, DocumentUpload upload) {
        return DocumentProcessTaskPayload.builder()
                .taskId(task.getId())
                .documentId(upload.getDocumentId())
                .spaceId(upload.getSpaceId())
                .knowledgeBaseId(upload.getKnowledgeBaseId())
                .fileMd5(upload.getFileMd5())
                .objectKey(upload.getObjectKey())
                .fileName(upload.getFileName())
                .contentType(upload.getContentType())
                .uploadedBy(upload.getUserId())
                .createdAt(Instant.now())
                .build();
    }

    private DocumentUpload loadUploadForWrite(Long uploadId) {
        return documentUploadRepository.findByIdForUpdate(uploadId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UPLOAD_NOT_FOUND));
    }

    private void requireUploadPermission(Long userId, DocumentUpload upload) {
        resourceAccessService.requireUploadDocument(userId, upload.getSpaceId());
        if (!upload.getUserId().equals(userId) && !resourceAccessService.canUploadDocument(userId, upload.getSpaceId())) {
            throw new BusinessException(ErrorCode.UPLOAD_ACCESS_DENIED);
        }
    }

    private void requireUploadViewPermission(Long userId, DocumentUpload upload) {
        resourceAccessService.requireViewSpace(userId, upload.getSpaceId());
        if (!upload.getUserId().equals(userId) && !resourceAccessService.canUploadDocument(userId, upload.getSpaceId())) {
            throw new BusinessException(ErrorCode.UPLOAD_ACCESS_DENIED);
        }
    }

    private void validateInit(InitUploadRequest request) {
        long expected = (long) request.getChunkSize() * request.getTotalChunks();
        if (request.getTotalSize() > expected) {
            throw new BusinessException(ErrorCode.UPLOAD_INVALID_CHUNK, "totalSize exceeds chunkSize*totalChunks");
        }
        validateSupportedType(request.getFileName(), request.getContentType());
    }

    private void validateChunk(DocumentUpload upload, Integer chunkIndex, MultipartFile file) {
        if (upload.getStatus() == DocumentUploadStatus.CANCELLED || upload.getStatus() == DocumentUploadStatus.EXPIRED || upload.getStatus() == DocumentUploadStatus.DELETED) {
            throw new BusinessException(ErrorCode.UPLOAD_INVALID_CHUNK, "upload is not writable");
        }
        if (chunkIndex == null || chunkIndex < 0 || chunkIndex >= upload.getTotalChunks()) {
            throw new BusinessException(ErrorCode.UPLOAD_INVALID_CHUNK);
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.UPLOAD_INVALID_CHUNK, "chunk file is empty");
        }
        if (file.getSize() > upload.getChunkSize()) {
            throw new BusinessException(ErrorCode.UPLOAD_INVALID_CHUNK, "chunk size too large");
        }
    }

    private UploadChunkResponse chunkResponse(DocumentUpload upload, Integer chunkIndex, List<Integer> uploaded, boolean uploadedFlag) {
        return UploadChunkResponse.builder()
                .uploadId(upload.getId())
                .chunkIndex(chunkIndex)
                .uploaded(uploadedFlag)
                .uploadedChunks(uploaded)
                .totalChunks(upload.getTotalChunks())
                .progress(progress(uploaded.size(), upload.getTotalChunks()))
                .build();
    }

    private double progress(int uploadedCount, int totalChunks) {
        if (totalChunks <= 0) {
            return 0.0;
        }
        return ((double) uploadedCount * 100.0) / (double) totalChunks;
    }

    private Set<DocumentUploadStatus> completedUploadStatuses() {
        return EnumSet.of(DocumentUploadStatus.MERGED, DocumentUploadStatus.PROCESSING, DocumentUploadStatus.INDEXED);
    }

    private List<Integer> allChunks(int totalChunks) {
        return java.util.stream.IntStream.range(0, totalChunks)
                .boxed()
                .toList();
    }

    private String normalizeContentType(String value) {
        if (value == null || value.isBlank()) {
            return "application/octet-stream";
        }
        return value.trim();
    }

    private void validateSupportedType(String fileName, String contentType) {
        String resolved = resolveSupportedContentType(fileName, contentType);
        if (resolved == null) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE, "Unsupported document type");
        }
    }

    private String resolveSupportedContentType(String fileName, String contentType) {
        String extensionType = contentTypeFromFileName(fileName);
        String suppliedType = normalizedSuppliedContentType(contentType);
        if (suppliedType == null || "application/octet-stream".equals(suppliedType)) {
            return extensionType;
        }
        if (extensionType == null) {
            return null;
        }
        if (SUPPORTED_CONTENT_TYPES.contains(suppliedType)) {
            return suppliedType;
        }
        return null;
    }

    private String normalizedSuppliedContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        return contentType.split(";")[0].trim().toLowerCase();
    }

    private String contentTypeFromFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        String lower = fileName.trim().toLowerCase();
        if (lower.endsWith(".txt")) {
            return "text/plain";
        }
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return "text/markdown";
        }
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        return null;
    }

    private String buildChunkObjectKey(Long uploadId, int chunkIndex) {
        String prefix = resolveObjectPrefix();
        return prefix + "/uploads/" + uploadId + "/chunks/" + chunkIndex;
    }

    private String buildObjectKey(String contentHash) {
        String prefix = resolveObjectPrefix();
        return prefix + "/objects/" + contentHash;
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

    private String md5Hex(MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.update(file.getBytes());
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.UPLOAD_INVALID_CHUNK, "failed to calculate chunk md5");
        }
    }

    private String computeSha256FromChunks(List<UploadChunk> chunks) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (UploadChunk chunk : chunks) {
                try (InputStream in = fileStorageService.getObject(currentBucket(), chunk.getObjectKey())) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = in.read(buffer)) > 0) {
                        digest.update(buffer, 0, len);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.UPLOAD_MERGE_FAILED, "failed to compute content hash");
        }
    }

    private void removeChunkObjects(List<UploadChunk> chunks) {
        String bucket = currentBucket();
        for (UploadChunk chunk : chunks) {
            if (fileStorageService.objectExists(bucket, chunk.getObjectKey())) {
                fileStorageService.removeObject(bucket, chunk.getObjectKey());
            }
        }
    }

    private void mergeChunkObjects(DocumentUpload upload, List<UploadChunk> chunks, String finalObjectKey) {
        String bucket = currentBucket();
        Vector<InputStream> streams = new Vector<>();
        try {
            for (UploadChunk chunk : chunks) {
                streams.add(fileStorageService.getObject(bucket, chunk.getObjectKey()));
            }
            try (SequenceInputStream merged = new SequenceInputStream(streams.elements())) {
                fileStorageService.putObject(
                        bucket,
                        finalObjectKey,
                        merged,
                        upload.getTotalSize(),
                        normalizeContentType(upload.getContentType())
                );
            }
        } catch (BusinessException ex) {
            for (InputStream stream : streams) {
                try {
                    stream.close();
                } catch (Exception ignored) {
                    // best-effort close
                }
            }
            throw ex;
        } catch (Exception ex) {
            for (InputStream stream : streams) {
                try {
                    stream.close();
                } catch (Exception ignored) {
                    // best-effort close
                }
            }
            throw new BusinessException(ErrorCode.UPLOAD_MERGE_FAILED, "failed to merge chunks");
        }
    }

    private String currentBucket() {
        if (normalizeTestRunId(storageProperties.paths().testRunId()) != null) {
            return fileStorageService.testBucket();
        }
        return fileStorageService.devBucket();
    }

    private boolean tryInstantReuse(Long userId, KnowledgeBase kb, DocumentUpload upload, String fileMd5) {
        DocumentUpload sourceUpload = documentUploadRepository
                .findFirstBySpaceIdAndFileMd5AndStatusInOrderByMergedAtDescIdDesc(
                        kb.getSpaceId(),
                        fileMd5,
                        INSTANT_REUSABLE_UPLOAD_STATUSES
                )
                .orElse(null);
        if (sourceUpload == null || sourceUpload.getDocumentId() == null || sourceUpload.getObjectKey() == null) {
            return false;
        }

        Document sourceDocument = documentRepository.findByIdAndDeletedAtIsNullAndStatusNot(
                sourceUpload.getDocumentId(),
                DocumentStatus.DELETED
        ).orElse(null);
        if (sourceDocument == null || sourceDocument.getFileObjectId() == null) {
            return false;
        }

        FileObject fileObject = fileObjectRepository.findById(sourceDocument.getFileObjectId()).orElse(null);
        if (fileObject == null || fileObject.getSpaceId() == null || !fileObject.getSpaceId().equals(kb.getSpaceId())) {
            return false;
        }
        if (fileObject.getStatus() != FileObjectStatus.ACTIVE) {
            return false;
        }
        if (!fileStorageService.objectExists(currentBucket(), fileObject.getObjectKey())) {
            return false;
        }

        Document document = new Document();
        document.setSpaceId(kb.getSpaceId());
        document.setKnowledgeBaseId(kb.getId());
        document.setFileObjectId(fileObject.getId());
        document.setTitle(upload.getFileName());
        document.setSourceType(DOCUMENT_SOURCE_TYPE);
        document.setObjectKey(fileObject.getObjectKey());
        document.setOriginalFilename(upload.getFileName());
        document.setContentHash(fileObject.getContentHash());
        document.setStatus(DocumentStatus.PENDING_PROCESS);
        document.setCreatedBy(userId);
        document = documentRepository.save(document);

        fileObject.setRefCount(fileObject.getRefCount() + 1);
        fileObjectRepository.save(fileObject);

        Map<String, Object> input = new HashMap<>();
        input.put("documentId", document.getId());
        input.put("spaceId", document.getSpaceId());
        input.put("knowledgeBaseId", document.getKnowledgeBaseId());
        input.put("fileMd5", upload.getFileMd5());
        input.put("objectKey", fileObject.getObjectKey());
        input.put("fileName", upload.getFileName());
        input.put("contentType", normalizeContentType(upload.getContentType()));
        input.put("uploadedBy", upload.getUserId());
        input.put("createdAt", Instant.now().toString());
        input.put("instantUpload", true);

        TaskResponse task = taskService.createTask(TaskCreateCommand.builder()
                .userId(userId)
                .spaceId(upload.getSpaceId())
                .taskType(TaskType.DOCUMENT_PROCESS)
                .targetType(DOCUMENT_TARGET_TYPE)
                .targetId(document.getId())
                .idempotencyKey("DOCUMENT_PROCESS:" + document.getId() + ":" + upload.getFileMd5())
                .input(input)
                .build());

        upload.setDocumentId(document.getId());
        upload.setTaskId(task.getId());
        upload.setObjectKey(fileObject.getObjectKey());
        upload.setStatus(DocumentUploadStatus.MERGED);
        upload.setMergedAt(LocalDateTime.now());
        documentUploadRepository.save(upload);

        upload.setStatus(DocumentUploadStatus.PROCESSING);
        documentUploadRepository.save(upload);
        uploadBitmapService.clear(upload.getId());
        return true;
    }

    private DocumentResponse toDocumentResponse(Document document) {
        return DocumentResponse.builder()
                .id(document.getId())
                .spaceId(document.getSpaceId())
                .knowledgeBaseId(document.getKnowledgeBaseId())
                .fileObjectId(document.getFileObjectId())
                .title(document.getTitle())
                .sourceType(document.getSourceType())
                .objectKey(document.getObjectKey())
                .originalFilename(document.getOriginalFilename())
                .contentHash(document.getContentHash())
                .activeIndexVersion(document.getActiveIndexVersion())
                .parsedTextObjectKey(document.getParsedTextObjectKey())
                .parseStatus(document.getParseStatus())
                .indexStatus(document.getIndexStatus())
                .tokenCount(document.getTokenCount())
                .chunkCount(document.getChunkCount())
                .errorMessage(document.getErrorMessage())
                .status(document.getStatus())
                .createdBy(document.getCreatedBy())
                .createdAt(document.getCreatedAt())
                .updatedAt(document.getUpdatedAt())
                .build();
    }
}
