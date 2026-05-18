package com.noteweave.admin.service;

import com.noteweave.admin.dto.CleanupExecuteRequest;
import com.noteweave.admin.dto.CleanupExecutionTaskPayload;
import com.noteweave.admin.dto.CleanupScanRequest;
import com.noteweave.admin.dto.OpsCleanupItemResponse;
import com.noteweave.admin.dto.OpsCleanupJobQuery;
import com.noteweave.admin.dto.OpsCleanupJobResponse;
import com.noteweave.admin.model.AuditAction;
import com.noteweave.admin.model.OpsCleanupItem;
import com.noteweave.admin.model.OpsCleanupItemStatus;
import com.noteweave.admin.model.OpsCleanupJob;
import com.noteweave.admin.model.OpsCleanupJobStatus;
import com.noteweave.admin.model.OpsCleanupJobType;
import com.noteweave.admin.repository.OpsCleanupItemRepository;
import com.noteweave.admin.repository.OpsCleanupJobRepository;
import com.noteweave.common.api.PageResponse;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.task.model.TaskType;
import com.noteweave.task.service.TaskCreateCommand;
import com.noteweave.task.service.TaskService;
import com.noteweave.task.worker.TaskExecutionCancelledException;
import com.noteweave.task.worker.TaskExecutionContext;
import com.noteweave.task.worker.TaskResult;
import com.noteweave.personal.source.repository.SourceRepository;
import com.noteweave.search.service.SearchIndexService;
import com.noteweave.space.model.SpaceStatus;
import com.noteweave.storage.service.FileStorageService;
import com.noteweave.team.document.model.DocumentStatus;
import com.noteweave.team.document.model.DocumentUpload;
import com.noteweave.team.document.model.DocumentUploadStatus;
import com.noteweave.team.document.model.FileObject;
import com.noteweave.team.document.model.UploadChunk;
import com.noteweave.team.document.repository.DocumentChunkRepository;
import com.noteweave.team.document.repository.DocumentRepository;
import com.noteweave.team.document.repository.DocumentUploadRepository;
import com.noteweave.team.document.repository.FileObjectRepository;
import com.noteweave.team.document.repository.UploadChunkRepository;
import com.noteweave.artifact.repository.ArtifactRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ResourceCleanupService {

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("id", "createdAt", "updatedAt");
    private static final Set<DocumentUploadStatus> EXPIRABLE_UPLOAD_STATUSES =
            Set.of(DocumentUploadStatus.INIT, DocumentUploadStatus.UPLOADING, DocumentUploadStatus.FAILED);

    private final OpsCleanupJobRepository jobRepository;
    private final OpsCleanupItemRepository itemRepository;
    private final DocumentUploadRepository documentUploadRepository;
    private final UploadChunkRepository uploadChunkRepository;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final FileObjectRepository fileObjectRepository;
    private final SourceRepository sourceRepository;
    private final ArtifactRepository artifactRepository;
    private final FileStorageService fileStorageService;
    private final AdminStorageSupport adminStorageSupport;
    private final SearchIndexService searchIndexService;
    private final JdbcTemplate jdbcTemplate;
    private final AuditLogService auditLogService;
    private final TaskService taskService;

    @Value("${noteweave.ops.soft-delete-retention-days:30}")
    private int defaultSoftDeleteRetentionDays;

    @Value("${noteweave.ops.cleanup-async-threshold:50}")
    private int cleanupAsyncThreshold;

    @Transactional
    public OpsCleanupJobResponse scan(Long operatorId, CleanupScanRequest request) {
        OpsCleanupJob job = new OpsCleanupJob();
        job.setJobType(request.getJobType());
        job.setStatus(OpsCleanupJobStatus.SCANNED);
        job.setTargetType(normalize(request.getTargetType()));
        job.setTargetId(request.getTargetId());
        job.setStartedBy(operatorId);
        job.setStartedAt(LocalDateTime.now());
        job.setFinishedAt(LocalDateTime.now());
        job = jobRepository.save(job);

        List<OpsCleanupItem> items = switch (request.getJobType()) {
            case UPLOAD_EXPIRED -> scanExpiredUploads(job);
            case FAILED_MERGE_OBJECT -> scanFailedMergeObjects(job);
            case MINIO_ORPHAN_OBJECT -> scanMinioOrphans(job);
            case ES_ORPHAN_INDEX -> scanEsOrphans(job);
            case SOFT_DELETE_PURGE -> scanSoftDeletePurge(job, request.getRetentionDays());
        };
        if (!items.isEmpty()) {
            itemRepository.saveAll(items);
        }
        job.setScanCount(items.size());
        jobRepository.save(job);
        return toResponse(job, true);
    }

    @Transactional
    public OpsCleanupJobResponse execute(Long operatorId, CleanupExecuteRequest request) {
        OpsCleanupJob job = jobRepository.findByIdForUpdate(request.getJobId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Cleanup job not found"));
        List<OpsCleanupItem> items = itemRepository.findByJobIdOrderByIdAsc(job.getId());
        if (items.size() >= cleanupAsyncThreshold) {
            return queueCleanupExecution(job, operatorId);
        }
        return executeNow(job, operatorId, items, null);
    }

    @Transactional
    public TaskResult executeQueuedJob(CleanupExecutionTaskPayload payload, TaskExecutionContext context) {
        OpsCleanupJob job = jobRepository.findByIdForUpdate(payload.getJobId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Cleanup job not found"));
        List<OpsCleanupItem> items = itemRepository.findByJobIdOrderByIdAsc(job.getId());
        try {
            OpsCleanupJobResponse response = executeNow(job, payload.getOperatorId(), items, context);
            return TaskResult.success(Map.of(
                    "jobId", response.getId(),
                    "status", response.getStatus().name(),
                    "cleanupCount", response.getCleanupCount(),
                    "scanCount", response.getScanCount()
            ));
        } catch (TaskExecutionCancelledException ex) {
            markJobFailed(job, "Cleanup task cancelled before completion");
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<OpsCleanupJobResponse> listJobs(OpsCleanupJobQuery query) {
        Pageable pageable = AdminPageSupport.buildPageable(
                query.getPage(),
                query.getPageSize(),
                query.getSort(),
                ALLOWED_SORT_FIELDS,
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
        Page<OpsCleanupJob> page = jobRepository.findAll(buildSpecification(query), pageable);
        return PageResponse.<OpsCleanupJobResponse>builder()
                .items(page.getContent().stream().map(job -> toResponse(job, false)).toList())
                .page(pageable.getPageNumber() + 1)
                .pageSize(pageable.getPageSize())
                .total(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .sort(AdminPageSupport.toSortExpression(pageable.getSort()))
                .filters(Map.of())
                .build();
    }

    @Transactional(readOnly = true)
    public OpsCleanupJobResponse getJob(Long jobId) {
        OpsCleanupJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Cleanup job not found"));
        return toResponse(job, true);
    }

    private List<OpsCleanupItem> scanExpiredUploads(OpsCleanupJob job) {
        LocalDateTime now = LocalDateTime.now();
        return documentUploadRepository.findByExpiresAtBeforeAndStatusIn(now, EXPIRABLE_UPLOAD_STATUSES).stream()
                .map(upload -> buildItem(job, "DOCUMENT_UPLOAD", upload.getId(), upload.getObjectKey(), "UPLOAD_EXPIRED"))
                .toList();
    }

    private List<OpsCleanupItem> scanFailedMergeObjects(OpsCleanupJob job) {
        List<OpsCleanupItem> items = new ArrayList<>();
        for (DocumentUpload upload : documentUploadRepository.findByStatusIn(List.of(DocumentUploadStatus.FAILED, DocumentUploadStatus.MERGED, DocumentUploadStatus.PROCESSING))) {
            List<UploadChunk> chunks = uploadChunkRepository.findByUploadIdOrderByChunkIndexAsc(upload.getId());
            boolean hasChunkResidue = !chunks.isEmpty();
            boolean finalObjectMissing = upload.getObjectKey() != null && !fileStorageService.objectExists(adminStorageSupport.currentBucket(), upload.getObjectKey());
            if (hasChunkResidue || finalObjectMissing) {
                items.add(buildItem(job, "DOCUMENT_UPLOAD", upload.getId(), upload.getObjectKey(), "FAILED_MERGE_OBJECT"));
            }
        }
        return items;
    }

    private List<OpsCleanupItem> scanMinioOrphans(OpsCleanupJob job) {
        String bucket = adminStorageSupport.currentBucket();
        Set<String> referenced = referencedObjectKeys();
        return fileStorageService.listObjectKeys(bucket, adminStorageSupport.currentObjectPrefix()).stream()
                .filter(key -> !referenced.contains(key))
                .map(key -> buildItem(job, "MINIO_OBJECT", null, key, "MINIO_ORPHAN_OBJECT"))
                .toList();
    }

    private List<OpsCleanupItem> scanEsOrphans(OpsCleanupJob job) {
        List<SearchIndexService.IndexedChunkDocument> indexedChunks = searchIndexService.listIndexedChunkDocuments(2000);
        Set<Long> existingChunkIds = new HashSet<>(documentChunkRepository.findByIdIn(
                        indexedChunks.stream().map(SearchIndexService.IndexedChunkDocument::chunkId).filter(java.util.Objects::nonNull).toList())
                .stream()
                .map(com.noteweave.team.document.model.DocumentChunk::getId)
                .toList());
        List<OpsCleanupItem> items = new ArrayList<>();
        for (SearchIndexService.IndexedChunkDocument indexedChunk : indexedChunks) {
            if (indexedChunk.chunkId() == null || !existingChunkIds.contains(indexedChunk.chunkId())) {
                items.add(buildItem(job, "DOCUMENT_CHUNK", indexedChunk.chunkId(), indexedChunk.esDocId(), "ES_ORPHAN_INDEX"));
            }
        }
        return items;
    }

    private List<OpsCleanupItem> scanSoftDeletePurge(OpsCleanupJob job, Integer retentionDays) {
        int safeRetentionDays = retentionDays == null || retentionDays <= 0 ? defaultSoftDeleteRetentionDays : retentionDays;
        LocalDateTime cutoff = LocalDateTime.now().minusDays(safeRetentionDays);
        List<OpsCleanupItem> items = new ArrayList<>();
        for (Long documentId : jdbcTemplate.query(
                "select id from document where deleted_at is not null and deleted_at < ?",
                (rs, rowNum) -> rs.getLong(1),
                cutoff
        )) {
            items.add(buildItem(job, "DOCUMENT", documentId, null, "SOFT_DELETE_PURGE"));
        }
        for (Long artifactId : jdbcTemplate.query(
                "select id from artifact where deleted_at is not null and deleted_at < ?",
                (rs, rowNum) -> rs.getLong(1),
                cutoff
        )) {
            items.add(buildItem(job, "ARTIFACT", artifactId, null, "SOFT_DELETE_PURGE"));
        }
        return items;
    }

    private OpsCleanupJobResponse queueCleanupExecution(OpsCleanupJob job, Long operatorId) {
        ensureExecutableStatus(job.getStatus());
        job.setStatus(OpsCleanupJobStatus.QUEUED);
        job.setCleanupCount(0);
        job.setErrorMessage(null);
        job.setStartedAt(null);
        job.setFinishedAt(null);
        jobRepository.save(job);
        taskService.createTask(TaskCreateCommand.builder()
                .userId(operatorId)
                .spaceId(null)
                .taskType(TaskType.CLEANUP_RESOURCE)
                .targetType("OPS_CLEANUP_JOB")
                .targetId(job.getId())
                .idempotencyKey("CLEANUP_RESOURCE:OPS_CLEANUP_JOB:" + job.getId() + ":" + System.currentTimeMillis())
                .input(CleanupExecutionTaskPayload.builder()
                        .jobId(job.getId())
                        .operatorId(operatorId)
                        .build())
                .maxRetryCount(1)
                .build());
        return toResponse(job, true);
    }

    private OpsCleanupJobResponse executeNow(
            OpsCleanupJob job,
            Long operatorId,
            List<OpsCleanupItem> items,
            TaskExecutionContext context
    ) {
        ensureExecutableStatus(job.getStatus());
        job.setStatus(OpsCleanupJobStatus.RUNNING);
        job.setStartedAt(LocalDateTime.now());
        job.setFinishedAt(null);
        job.setCleanupCount(0);
        job.setErrorMessage(null);
        jobRepository.save(job);

        int successCount = 0;
        List<String> failures = new ArrayList<>();
        for (OpsCleanupItem item : items) {
            if (context != null) {
                context.ensureNotCancelled();
            }
            try {
                OpsCleanupItemStatus result = executeItem(job, item);
                item.setStatus(result);
                item.setErrorMessage(null);
                if (result == OpsCleanupItemStatus.SUCCESS) {
                    successCount++;
                }
            } catch (Exception ex) {
                item.setStatus(OpsCleanupItemStatus.FAILED);
                item.setErrorMessage(trim(ex.getMessage(), 1000));
                failures.add("item " + item.getId() + ": " + item.getErrorMessage());
            }
            itemRepository.save(item);
            if (context != null) {
                context.publishProgress(
                        "Cleanup item processed",
                        Map.of(
                                "jobId", job.getId(),
                                "itemId", item.getId(),
                                "itemStatus", item.getStatus().name(),
                                "successCount", successCount
                        )
                );
            }
        }

        job.setCleanupCount(successCount);
        job.setFinishedAt(LocalDateTime.now());
        job.setStatus(failures.isEmpty() ? OpsCleanupJobStatus.COMPLETED : OpsCleanupJobStatus.FAILED);
        job.setErrorMessage(failures.isEmpty() ? null : String.join("; ", failures));
        jobRepository.save(job);

        OpsCleanupJobResponse response = toResponse(job, true);
        auditLogService.record(
                operatorId,
                null,
                AuditAction.RESOURCE_CLEANUP,
                "OPS_CLEANUP_JOB",
                job.getId(),
                Map.of("jobId", job.getId(), "jobType", job.getJobType().name(), "scanCount", job.getScanCount()),
                response
        );
        return response;
    }

    private void ensureExecutableStatus(OpsCleanupJobStatus status) {
        if (status == OpsCleanupJobStatus.RUNNING || status == OpsCleanupJobStatus.QUEUED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Cleanup job is already in progress");
        }
        if (status == OpsCleanupJobStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Cleanup job has already completed");
        }
    }

    private void markJobFailed(OpsCleanupJob job, String message) {
        OpsCleanupJob managed = jobRepository.findByIdForUpdate(job.getId())
                .orElse(job);
        managed.setStatus(OpsCleanupJobStatus.FAILED);
        managed.setFinishedAt(LocalDateTime.now());
        managed.setErrorMessage(trim(message, 1000));
        jobRepository.save(managed);
    }

    private OpsCleanupItemStatus executeItem(OpsCleanupJob job, OpsCleanupItem item) {
        return switch (job.getJobType()) {
            case UPLOAD_EXPIRED -> executeExpiredUpload(item);
            case FAILED_MERGE_OBJECT -> executeFailedMergeCleanup(item);
            case MINIO_ORPHAN_OBJECT -> executeMinioObjectCleanup(item);
            case ES_ORPHAN_INDEX -> executeEsOrphanCleanup(item);
            case SOFT_DELETE_PURGE -> executeSoftDeletePurge(item);
        };
    }

    private OpsCleanupItemStatus executeExpiredUpload(OpsCleanupItem item) {
        DocumentUpload upload = documentUploadRepository.findByIdForUpdate(item.getTargetId()).orElse(null);
        if (upload == null || !EXPIRABLE_UPLOAD_STATUSES.contains(upload.getStatus())
                || upload.getExpiresAt() == null || upload.getExpiresAt().isAfter(LocalDateTime.now())) {
            return OpsCleanupItemStatus.SKIPPED;
        }
        upload.setStatus(DocumentUploadStatus.EXPIRED);
        documentUploadRepository.save(upload);
        cleanupUploadResidue(upload, false);
        return OpsCleanupItemStatus.SUCCESS;
    }

    private OpsCleanupItemStatus executeFailedMergeCleanup(OpsCleanupItem item) {
        DocumentUpload upload = documentUploadRepository.findByIdForUpdate(item.getTargetId()).orElse(null);
        if (upload == null) {
            return OpsCleanupItemStatus.SKIPPED;
        }
        cleanupUploadResidue(upload, true);
        return OpsCleanupItemStatus.SUCCESS;
    }

    private OpsCleanupItemStatus executeMinioObjectCleanup(OpsCleanupItem item) {
        String objectKey = item.getObjectKey();
        if (objectKey == null || objectKey.isBlank()) {
            return OpsCleanupItemStatus.SKIPPED;
        }
        if (referencedObjectKeys().contains(objectKey)) {
            return OpsCleanupItemStatus.SKIPPED;
        }
        if (fileStorageService.objectExists(adminStorageSupport.currentBucket(), objectKey)) {
            fileStorageService.removeObject(adminStorageSupport.currentBucket(), objectKey);
        }
        return OpsCleanupItemStatus.SUCCESS;
    }

    private OpsCleanupItemStatus executeEsOrphanCleanup(OpsCleanupItem item) {
        if (item.getTargetId() != null && documentChunkRepository.findById(item.getTargetId()).isPresent()) {
            return OpsCleanupItemStatus.SKIPPED;
        }
        searchIndexService.deleteChunkDocumentByEsDocId(item.getObjectKey());
        return OpsCleanupItemStatus.SUCCESS;
    }

    private OpsCleanupItemStatus executeSoftDeletePurge(OpsCleanupItem item) {
        if ("DOCUMENT".equals(item.getTargetType())) {
            return purgeDeletedDocument(item.getTargetId());
        }
        if ("ARTIFACT".equals(item.getTargetType())) {
            return purgeDeletedArtifact(item.getTargetId());
        }
        return OpsCleanupItemStatus.SKIPPED;
    }

    private OpsCleanupItemStatus purgeDeletedDocument(Long documentId) {
        var document = documentRepository.findByIdForUpdate(documentId).orElse(null);
        if (document == null || document.getDeletedAt() == null || document.getStatus() != DocumentStatus.DELETED) {
            return OpsCleanupItemStatus.SKIPPED;
        }
        FileObject fileObject = document.getFileObjectId() == null ? null : fileObjectRepository.findByIdForUpdate(document.getFileObjectId()).orElse(null);
        searchIndexService.deleteByDocumentId(documentId);
        documentChunkRepository.deleteByDocumentId(documentId);
        jdbcTemplate.update("delete from document_upload where document_id = ?", documentId);
        documentRepository.delete(document);
        if (fileObject != null) {
            int nextRefCount = Math.max(0, fileObject.getRefCount() - 1);
            fileObject.setRefCount(nextRefCount);
            if (nextRefCount == 0) {
                if (fileObject.getObjectKey() != null && fileStorageService.objectExists(adminStorageSupport.currentBucket(), fileObject.getObjectKey())) {
                    fileStorageService.removeObject(adminStorageSupport.currentBucket(), fileObject.getObjectKey());
                }
                fileObjectRepository.delete(fileObject);
            } else {
                fileObjectRepository.save(fileObject);
            }
        }
        return OpsCleanupItemStatus.SUCCESS;
    }

    private OpsCleanupItemStatus purgeDeletedArtifact(Long artifactId) {
        var artifact = artifactRepository.findByIdForUpdate(artifactId).orElse(null);
        if (artifact == null || artifact.getDeletedAt() == null) {
            return OpsCleanupItemStatus.SKIPPED;
        }
        jdbcTemplate.update("delete from artifact_card_relation where artifact_id = ?", artifactId);
        jdbcTemplate.update("delete from artifact_citation where artifact_id = ?", artifactId);
        jdbcTemplate.update("delete from artifact_source where artifact_id = ?", artifactId);
        jdbcTemplate.update("delete from artifact_version where artifact_id = ?", artifactId);
        jdbcTemplate.update("delete from session_artifact where artifact_id = ?", artifactId);
        jdbcTemplate.update("delete from artifact_distillation_proposal where artifact_id = ?", artifactId);
        artifactRepository.delete(artifact);
        return OpsCleanupItemStatus.SUCCESS;
    }

    private void cleanupUploadResidue(DocumentUpload upload, boolean clearMissingObjectKey) {
        List<UploadChunk> chunks = uploadChunkRepository.findByUploadIdOrderByChunkIndexAsc(upload.getId());
        for (UploadChunk chunk : chunks) {
            if (chunk.getObjectKey() != null && fileStorageService.objectExists(adminStorageSupport.currentBucket(), chunk.getObjectKey())) {
                fileStorageService.removeObject(adminStorageSupport.currentBucket(), chunk.getObjectKey());
            }
        }
        uploadChunkRepository.deleteByUploadId(upload.getId());
        if (clearMissingObjectKey && upload.getDocumentId() == null && upload.getObjectKey() != null
                && fileStorageService.objectExists(adminStorageSupport.currentBucket(), upload.getObjectKey())) {
            fileStorageService.removeObject(adminStorageSupport.currentBucket(), upload.getObjectKey());
            upload.setObjectKey(null);
            documentUploadRepository.save(upload);
        }
    }

    private Set<String> referencedObjectKeys() {
        Set<String> keys = new HashSet<>();
        addIfPresent(keys, documentUploadRepository.findAll().stream().map(DocumentUpload::getObjectKey).toList());
        addIfPresent(keys, uploadChunkRepository.findAll().stream().map(UploadChunk::getObjectKey).toList());
        addIfPresent(keys, fileObjectRepository.findAll().stream().map(FileObject::getObjectKey).toList());
        addIfPresent(keys, documentRepository.findAll().stream().flatMap(document -> java.util.stream.Stream.of(document.getObjectKey(), document.getParsedTextObjectKey())).toList());
        addIfPresent(keys, sourceRepository.findAll().stream().flatMap(source -> java.util.stream.Stream.of(source.getObjectKey(), source.getRawTextObjectKey(), source.getParsedTextObjectKey())).toList());
        return keys;
    }

    private void addIfPresent(Set<String> target, Collection<String> values) {
        values.stream()
                .filter(value -> value != null && !value.isBlank())
                .forEach(target::add);
    }

    private Specification<OpsCleanupJob> buildSpecification(OpsCleanupJobQuery query) {
        return (root, criteriaQuery, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query.getJobType() != null) {
                predicates.add(criteriaBuilder.equal(root.get("jobType"), query.getJobType()));
            }
            if (query.getStatus() != null) {
                predicates.add(criteriaBuilder.equal(root.get("status"), query.getStatus()));
            }
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private OpsCleanupItem buildItem(OpsCleanupJob job, String targetType, Long targetId, String objectKey, String reason) {
        OpsCleanupItem item = new OpsCleanupItem();
        item.setJobId(job.getId());
        item.setTargetType(targetType);
        item.setTargetId(targetId);
        item.setObjectKey(normalize(objectKey));
        item.setReason(reason);
        item.setStatus(OpsCleanupItemStatus.PENDING);
        return item;
    }

    private OpsCleanupJobResponse toResponse(OpsCleanupJob job, boolean includeItems) {
        return OpsCleanupJobResponse.builder()
                .id(job.getId())
                .jobType(job.getJobType())
                .status(job.getStatus())
                .targetType(job.getTargetType())
                .targetId(job.getTargetId())
                .scanCount(job.getScanCount())
                .cleanupCount(job.getCleanupCount())
                .errorMessage(job.getErrorMessage())
                .startedBy(job.getStartedBy())
                .startedAt(job.getStartedAt())
                .finishedAt(job.getFinishedAt())
                .createdAt(job.getCreatedAt())
                .updatedAt(job.getUpdatedAt())
                .items(includeItems ? itemRepository.findByJobIdOrderByIdAsc(job.getId()).stream().map(this::toItemResponse).toList() : null)
                .build();
    }

    private OpsCleanupItemResponse toItemResponse(OpsCleanupItem item) {
        return OpsCleanupItemResponse.builder()
                .id(item.getId())
                .jobId(item.getJobId())
                .targetType(item.getTargetType())
                .targetId(item.getTargetId())
                .objectKey(item.getObjectKey())
                .reason(item.getReason())
                .status(item.getStatus())
                .errorMessage(item.getErrorMessage())
                .createdAt(item.getCreatedAt())
                .updatedAt(item.getUpdatedAt())
                .build();
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String trim(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
