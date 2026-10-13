package com.noteweave.upload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.noteweave.storage.ObjectStorage;
import com.noteweave.source.SourceMessagingMode;
import com.noteweave.source.SourceParsePort;
import com.noteweave.source.SourceWikiCommandPort;
import com.noteweave.source.SourceCatalogVersionService;
import com.noteweave.task.TaskCommandPort;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;
import com.noteweave.security.AuditActorProvider;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);
    private static final String BUCKET_SOURCE = "noteweave-source";

    private final JdbcTemplate jdbcTemplate;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final ObjectStorage storage;
    private final SourceParsePort sourceParsePort;
    private final SourceWikiCommandPort wikiCommandPort;
    private final TaskCommandPort taskCommandPort;
    private final ObjectMapper objectMapper;
    private final SourceMessagingMode messagingMode;
    private final UploadSecurityPolicy uploadSecurityPolicy;
    private final AuditActorProvider auditActorProvider;
    private final SourceCatalogVersionService sourceCatalogVersionService;
    private final UploadTempObjectCleanupService uploadTempObjectCleanupService;
    private Duration staleAfter = Duration.ofSeconds(86400);
    private com.noteweave.research.ResearchGeneratedSourceReadGate generatedSourceReadGate;

    @Value("${noteweave.upload.cleanup-stale-after-seconds:86400}")
    void configureStaleAfter(long seconds) { staleAfter = Duration.ofSeconds(Math.max(60, seconds)); }

    @Autowired
    void configureGeneratedSourceReadGate(com.noteweave.research.ResearchGeneratedSourceReadGate gate) {
        generatedSourceReadGate = gate;
    }

    public UploadService(
            JdbcTemplate jdbcTemplate,
            WorkspaceAccessGuard workspaceAccessGuard,
            ObjectStorage storage,
            SourceParsePort sourceParsePort,
            SourceWikiCommandPort wikiCommandPort,
            TaskCommandPort taskCommandPort,
            ObjectMapper objectMapper,
            SourceMessagingMode messagingMode,
            UploadSecurityPolicy uploadSecurityPolicy,
            AuditActorProvider auditActorProvider,
            SourceCatalogVersionService sourceCatalogVersionService,
            UploadTempObjectCleanupService uploadTempObjectCleanupService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.storage = storage;
        this.sourceParsePort = sourceParsePort;
        this.wikiCommandPort = wikiCommandPort;
        this.taskCommandPort = taskCommandPort;
        this.objectMapper = objectMapper;
        this.messagingMode = messagingMode;
        this.uploadSecurityPolicy = uploadSecurityPolicy;
        this.auditActorProvider = auditActorProvider;
        this.sourceCatalogVersionService = sourceCatalogVersionService;
        this.uploadTempObjectCleanupService = uploadTempObjectCleanupService;
        log.info("UploadService initialised with object storage backend: {}", storage.backendName());
    }

    @Transactional
    public CreateUploadResponse createUpload(String workspaceId, CreateUploadRequest request) {
        uploadSecurityPolicy.validateMetadata(request);
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.SOURCE_WRITE);
        String actor = auditActorProvider.currentOrSystem("UPLOAD");
        String uploadId = Ids.newId();
        CompleteUploadResponse instant = findInstantSource(workspaceId, request);
        jdbcTemplate.update("""
                insert into document_upload(
                    id, workspace_id, file_name, file_size, mime_type, chunk_size, total_chunks, status,
                    created_by, updated_by, expected_sha256, source_id, task_id
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, uploadId, workspaceId, request.fileName(), request.fileSize(), request.mimeType(),
                request.chunkSize(), request.totalChunks(), instant == null ? "UPLOADING" : "COMPLETED", actor, actor,
                request.sha256(), instant == null ? null : instant.sourceId(), instant == null ? null : instant.taskId());
        if (instant != null) {
            return new CreateUploadResponse(uploadId, "COMPLETED", request.chunkSize(), request.totalChunks(),
                    true, instant.sourceId(), instant.taskId(), instant.parseStatus(), instant.indexStatus());
        }
        return new CreateUploadResponse(uploadId, "UPLOADING", request.chunkSize(), request.totalChunks());
    }

    public List<UploadFormatResponse> formats() { return uploadSecurityPolicy.formats(); }

    public UploadStatusResponse getUpload(String uploadId) {
        UploadRow upload = findUpload(uploadId);
        workspaceAccessGuard.requirePermission(upload.workspaceId(), WorkspacePermission.SOURCE_WRITE);
        requireLiveSession(upload);
        List<Integer> indexes = jdbcTemplate.queryForList("""
                select chunk_index from upload_chunk where upload_id = ? order by chunk_index
                """, Integer.class, uploadId);
        return new UploadStatusResponse(uploadId, upload.status(), upload.totalChunks(), upload.chunkSize(), indexes);
    }

    private void requireLiveSession(UploadRow upload) {
        if ("EXPIRED".equals(upload.status()) || ("UPLOADING".equals(upload.status())
                && upload.updatedAt().isBefore(Instant.now().minus(staleAfter)))) {
            throw new BusinessException("UPLOAD_NOT_FOUND", "上传会话不存在或已过期", HttpStatus.NOT_FOUND);
        }
    }

    private CompleteUploadResponse findInstantSource(String workspaceId, CreateUploadRequest request) {
        if (request.sha256() == null) return null;
        List<FileObjectRef> files = jdbcTemplate.query("""
                select id, object_key, file_size from file_object where workspace_id = ? and sha256 = ?
                """, (rs, index) -> new FileObjectRef(rs.getString(1), rs.getString(2), rs.getLong(3)), workspaceId, request.sha256());
        if (files.isEmpty()) return null;
        FileObjectRef file = files.get(0);
        if (file.size() != request.fileSize()) {
            throw new BusinessException("UPLOAD_FILE_SIZE_MISMATCH", "同哈希文件的大小与声明不一致");
        }
        if (!storage.exists(BUCKET_SOURCE, file.objectKey())) return null;
        List<InstantSource> sources = jdbcTemplate.query("""
                select s.id, s.parse_status, s.index_status, coalesce(s.generated_by, ''), coalesce(s.generated_ref_id, '')
                from source s where s.workspace_id = ? and s.file_object_id = ?
                  and s.status in ('READY', 'PROCESSING') and s.parse_status <> 'FAILED'
                  and exists (select 1 from source_snapshot ss where ss.source_id = s.id
                    and ss.file_object_id = s.file_object_id
                    and ss.version_no = (select max(latest_snapshot.version_no) from source_snapshot latest_snapshot where latest_snapshot.source_id = s.id))
                order by s.created_at desc
                """, (rs, index) -> new InstantSource(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)),
                workspaceId, file.id());
        for (InstantSource source : sources) {
            if ("research_agent".equals(source.generatedBy())) {
                if (generatedSourceReadGate == null) continue;
                try {
                    if (!generatedSourceReadGate.visible(workspaceId, source.generatedBy(), source.generatedRefId())) continue;
                } catch (BusinessException unavailable) { continue; }
            }
            List<String> tasks = jdbcTemplate.queryForList("""
                    select id from task where workspace_id = ? and target_id = ? and target_type = 'SOURCE'
                      and task_type = 'SOURCE_PARSE' and task_status not in ('COMPLETED', 'FAILED', 'CANCELLED')
                    order by created_at desc limit 1
                    """, String.class, workspaceId, source.id());
            return new CompleteUploadResponse(source.id(), tasks.isEmpty() ? "" : tasks.get(0), source.parseStatus(), source.indexStatus());
        }
        return null;
    }

    private record InstantSource(String id, String parseStatus, String indexStatus, String generatedBy, String generatedRefId) {}

    @Transactional
    public UploadChunkResponse acceptChunk(String uploadId, int chunkIndex, String contentMd5, byte[] content) {
        UploadRow upload = findUploadForUpdate(uploadId);
        workspaceAccessGuard.requirePermission(upload.workspaceId(), WorkspacePermission.SOURCE_WRITE);
        requireLiveSession(upload);
        String actor = auditActorProvider.currentOrSystem("UPLOAD");
        if (!"UPLOADING".equals(upload.status())) {
            throw new BusinessException("UPLOAD_NOT_WRITABLE", "当前上传事务不可继续写入");
        }
        if (chunkIndex < 0 || chunkIndex >= upload.totalChunks()) {
            throw new BusinessException("UPLOAD_CHUNK_OUT_OF_RANGE", "分片序号超出范围");
        }
        verifyContentMd5(contentMd5, content);
        uploadSecurityPolicy.validateChunk(chunkIndex, upload.totalChunks(), upload.chunkSize(), content);
        String objectKey = "workspace/%s/upload_tmp/%s/%d".formatted(upload.workspaceId(), uploadId, chunkIndex);
        storage.write(BUCKET_SOURCE, objectKey, content);
        int updated = jdbcTemplate.update("""
                update upload_chunk
                set content_md5 = ?, object_key = ?, byte_size = ?, created_at = current_timestamp
                where upload_id = ? and chunk_index = ?
                  and exists (
                      select 1 from document_upload u
                      where u.id = upload_chunk.upload_id and u.workspace_id = ?
                  )
                """, contentMd5, objectKey, content.length, uploadId, chunkIndex, upload.workspaceId());
        if (updated == 0) {
            jdbcTemplate.update("""
                    insert into upload_chunk(id, upload_id, chunk_index, content_md5, object_key, byte_size)
                    values (?, ?, ?, ?, ?, ?)
                    """, Ids.newId(), uploadId, chunkIndex, contentMd5, objectKey, content.length);
            jdbcTemplate.update("""
                    update document_upload
                    set uploaded_chunks = uploaded_chunks + 1, updated_by = ?, updated_at = current_timestamp
                    where workspace_id = ? and id = ?
                    """, actor, upload.workspaceId(), uploadId);
        } else {
            jdbcTemplate.update("update document_upload set updated_by = ?, updated_at = current_timestamp where workspace_id = ? and id = ?",
                    actor, upload.workspaceId(), uploadId);
        }
        return new UploadChunkResponse(uploadId, chunkIndex, true);
    }

    @Transactional
    public CompleteUploadResponse completeUpload(String uploadId) {
        UploadRow upload = findUploadForUpdate(uploadId);
        workspaceAccessGuard.requirePermission(upload.workspaceId(), WorkspacePermission.SOURCE_WRITE);
        requireLiveSession(upload);
        String actor = auditActorProvider.currentOrSystem("UPLOAD");
        if ("COMPLETED".equals(upload.status()) && upload.sourceId() != null && upload.taskId() != null) {
            cleanupAfterCommit(uploadId);
            return sourceResult(upload.workspaceId(), upload.sourceId(), upload.taskId());
        }
        if (!"UPLOADING".equals(upload.status())) {
            throw new BusinessException(
                    "UPLOAD_NOT_COMPLETABLE",
                    "Upload transaction can no longer be completed",
                    HttpStatus.CONFLICT
            );
        }
        List<Map<String, Object>> chunkRows = jdbcTemplate.queryForList("""
                select c.chunk_index, c.object_key, c.byte_size
                from upload_chunk c
                join document_upload u on u.id = c.upload_id
                where u.workspace_id = ? and c.upload_id = ?
                order by c.chunk_index
                """, upload.workspaceId(), uploadId);
        if (chunkRows.size() != upload.totalChunks()) {
            throw new BusinessException("UPLOAD_CHUNK_INCOMPLETE", "上传分片尚未完整");
        }
        long size = 0;
        for (int index = 0; index < chunkRows.size(); index++) {
            Map<String, Object> chunk = chunkRows.get(index);
            if (((Number) chunk.get("chunk_index")).intValue() != index) {
                throw new BusinessException("UPLOAD_CHUNK_INCOMPLETE", "上传分片序号不完整");
            }
            size = Math.addExact(size, ((Number) chunk.get("byte_size")).longValue());
        }
        if (size != upload.fileSize()) throw new BusinessException("UPLOAD_FILE_SIZE_MISMATCH", "上传分片大小与声明不一致");
        MergedFile merged = mergeChunksToFile(upload, chunkRows);
        try {
            return completeWithMergedFile(upload, uploadId, actor, merged);
        } finally {
            deleteQuietly(merged.path());
        }
    }

    private CompleteUploadResponse completeWithMergedFile(UploadRow upload, String uploadId, String actor,
                                                          MergedFile merged) {
        uploadSecurityPolicy.validateMergedFile(upload.mimeType(), upload.fileSize(), merged.path());
        String sha256 = merged.sha256();
        if (upload.expectedSha256() != null && !upload.expectedSha256().equals(sha256)) {
            throw new BusinessException("UPLOAD_SHA256_MISMATCH", "完整文件的 SHA-256 与创建上传时的声明不一致");
        }
        FileObjectRef fileObject = getOrCreateFileObject(upload, sha256, upload.fileSize(), merged.objectKey());
        String sourceId = Ids.newId();
        String snapshotId = Ids.newId();
        String objectKey = "workspace/%s/source/%s/snapshot/1/original/%s".formatted(upload.workspaceId(), sourceId, sanitize(upload.fileName()));
        // 快照原件从文件对象服务端复制，不再经过应用内存
        cleanupObjectOnRollback(objectKey);
        storage.copy(BUCKET_SOURCE, fileObject.objectKey(), BUCKET_SOURCE, objectKey);
        jdbcTemplate.update("""
                insert into source(
                    id, workspace_id, file_object_id, title, source_type, status, parse_status, index_status,
                    created_by, updated_by
                ) values (?, ?, ?, ?, 'USER_UPLOAD', 'PROCESSING', 'PENDING', 'PENDING', ?, ?)
                """, sourceId, upload.workspaceId(), fileObject.id(), upload.fileName(), actor, actor);
        sourceCatalogVersionService.bump(upload.workspaceId());
        jdbcTemplate.update("""
                insert into source_snapshot(id, source_id, file_object_id, version_no, object_key, sha256, parse_status, index_status)
                values (?, ?, ?, 1, ?, ?, 'PENDING', 'PENDING')
                """, snapshotId, sourceId, fileObject.id(), objectKey, sha256);
        String taskId = taskCommandPort.createTask(
                upload.workspaceId(), "SOURCE_PARSE", "SOURCE", sourceId, "PARSING", "资料解析与切片");
        Map<String, Object> payload = Map.of(
                "taskId", taskId,
                "sourceId", sourceId,
                "snapshotId", snapshotId,
                "workspaceId", upload.workspaceId()
        );
        // 当 Kafka 关闭时（开发/测试场景），同步执行解析以保证上传后立刻可用。
        // 生产环境（Docker / 启用 Kafka）下，异步消费会接管，状态会从 PARSING_QUEUED 推进到 PARSED。
        boolean kafkaEnabled = messagingMode.isAsyncEnabled();
        String finalParseStatus = "PARSING_QUEUED";
        String finalIndexStatus = "INDEX_QUEUED";
        if (kafkaEnabled) {
            jdbcTemplate.update("""
                    insert into task_outbox(id, task_id, topic, message_key, payload_json, status)
                    values (?, ?, 'noteweave.source.parse', ?, ?, 'READY')
                    """, Ids.newId(), taskId, sourceId, Json.write(objectMapper, payload));
        } else {
            taskCommandPort.startTask(taskId);
            sourceParsePort.parseAndIndex(upload.workspaceId(), sourceId, snapshotId, merged.path());
            Map<String, String> sourceState = jdbcTemplate.queryForObject("""
                    select parse_status, index_status from source
                    where workspace_id = ? and id = ?
                    """, (rs, rowNum) -> Map.of(
                    "parse_status", rs.getString("parse_status"),
                    "index_status", rs.getString("index_status")), upload.workspaceId(), sourceId);
            finalParseStatus = sourceState.get("parse_status");
            finalIndexStatus = sourceState.get("index_status");
        }

        // Wiki ingest only accepts a source that can enter the indexed READY lifecycle.
        // A local-only parse must remain usable as PARSED/DISABLED without turning upload
        // completion into a false 500 or fabricating an indexed state.
        if (!"DISABLED".equals(finalIndexStatus)) {
            wikiCommandPort.requestSourceIngest(upload.workspaceId(), sourceId);
        }

        int completed = jdbcTemplate.update("""
                update document_upload
                set status = 'COMPLETED', source_id = ?, task_id = ?, updated_by = ?, updated_at = current_timestamp
                where workspace_id = ? and id = ? and status = 'UPLOADING'
                """, sourceId, taskId, actor, upload.workspaceId(), uploadId);
        if (completed != 1) {
            throw new BusinessException(
                    "UPLOAD_COMPLETION_CONFLICT",
                    "Upload transaction was completed concurrently",
                    HttpStatus.CONFLICT
            );
        }
        cleanupAfterCommit(uploadId);
        return new CompleteUploadResponse(sourceId, taskId, finalParseStatus, finalIndexStatus);
    }

    private void cleanupAfterCommit(String uploadId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            uploadTempObjectCleanupService.cleanupAfterCommit(uploadId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                uploadTempObjectCleanupService.cleanupAfterCommit(uploadId);
            }
        });
    }

    /** 只登记本次事务独占的新对象，复用的文件对象不参与回滚删除。 */
    private void cleanupObjectOnRollback(String objectKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) return;
                try {
                    storage.delete(BUCKET_SOURCE, objectKey);
                } catch (RuntimeException ex) {
                    log.warn("上传回滚对象清理失败：objectKey={}", objectKey, ex);
                }
            }
        });
    }

    private CompleteUploadResponse sourceResult(String workspaceId, String sourceId, String taskId) {
        return jdbcTemplate.query("""
                select parse_status, index_status from source where workspace_id = ? and id = ? and status <> 'DELETED'
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("SOURCE_NOT_FOUND", "资料不存在", HttpStatus.NOT_FOUND);
            }
            return new CompleteUploadResponse(sourceId, taskId, rs.getString("parse_status"), rs.getString("index_status"));
        }, workspaceId, sourceId);
    }

    private MergedFile mergeChunksToFile(UploadRow upload, List<Map<String, Object>> chunkRows) {
        java.nio.file.Path file = null;
        try {
            file = java.nio.file.Files.createTempFile("noteweave-upload-", ".bin");
            String mergedKey = "workspace/%s/upload_tmp/%s/merged".formatted(upload.workspaceId(), upload.id());
            storage.compose(BUCKET_SOURCE, chunkRows.stream().map(row -> (String) row.get("object_key")).toList(), BUCKET_SOURCE, mergedKey);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (java.io.InputStream input = new java.security.DigestInputStream(storage.openStream(BUCKET_SOURCE, mergedKey), digest);
                 java.io.OutputStream output = new java.io.BufferedOutputStream(java.nio.file.Files.newOutputStream(file))) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > upload.fileSize()) throw new BusinessException("UPLOAD_FILE_SIZE_MISMATCH", "合成对象超过声明大小");
                    output.write(buffer, 0, count);
                }
            }
            if (total != upload.fileSize()) throw new BusinessException("UPLOAD_FILE_SIZE_MISMATCH", "合成对象大小与声明不一致");
            return new MergedFile(file, HexFormat.of().formatHex(digest.digest()), mergedKey);
        } catch (BusinessException ex) {
            deleteQuietly(file);
            throw ex;
        } catch (Exception ex) {
            deleteQuietly(file);
            throw new BusinessException("UPLOAD_MERGE_FAILED", "上传分片合并失败");
        }
    }

    private static void deleteQuietly(java.nio.file.Path file) {
        if (file == null) return;
        try {
            java.nio.file.Files.deleteIfExists(file);
        } catch (java.io.IOException ex) {
            log.warn("Failed to delete merged upload temp file {}", file);
        }
    }

    private record MergedFile(java.nio.file.Path path, String sha256, String objectKey) {
    }

    private FileObjectRef getOrCreateFileObject(UploadRow upload, String sha256, long size, String mergedKey) {
        List<FileObjectRef> existing = jdbcTemplate.query("""
                select id, object_key, file_size from file_object where workspace_id = ? and sha256 = ?
                """, (rs, rowNum) -> new FileObjectRef(rs.getString("id"), rs.getString("object_key"), rs.getLong("file_size")), upload.workspaceId(), sha256);
        if (!existing.isEmpty()) {
            FileObjectRef ref = existing.get(0);
            if (ref.size() != size) throw new BusinessException("UPLOAD_FILE_SIZE_MISMATCH", "同哈希对象的文件大小不一致");
            if (!storage.exists(BUCKET_SOURCE, ref.objectKey())) {
                storage.copy(BUCKET_SOURCE, mergedKey, BUCKET_SOURCE, ref.objectKey());
            }
            jdbcTemplate.update("""
                    update file_object set ref_count = ref_count + 1
                    where workspace_id = ? and id = ?
                    """, upload.workspaceId(), ref.id());
            return ref;
        }
        String fileObjectId = Ids.newId();
        // 每次新建使用独立对象键，避免同哈希并发事务回滚时误删另一事务的对象。
        String objectKey = "workspace/%s/file_object/%s/%s-%s".formatted(upload.workspaceId(), fileObjectId, sha256, sanitize(upload.fileName()));
        cleanupObjectOnRollback(objectKey);
        storage.copy(BUCKET_SOURCE, mergedKey, BUCKET_SOURCE, objectKey);
        jdbcTemplate.update("""
                insert into file_object(id, workspace_id, object_key, sha256, file_size, mime_type, ref_count)
                values (?, ?, ?, ?, ?, ?, 1)
                """, fileObjectId, upload.workspaceId(), objectKey, sha256, size, upload.mimeType());
        return new FileObjectRef(fileObjectId, objectKey, size);
    }

    private UploadRow findUpload(String uploadId) {
        return jdbcTemplate.query("""
                select id, workspace_id, file_name, file_size, mime_type, chunk_size, total_chunks, status, source_id, task_id, expected_sha256, updated_at
                from document_upload where id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("UPLOAD_NOT_FOUND", "上传事务不存在", HttpStatus.NOT_FOUND);
            }
            return new UploadRow(
                    rs.getString("id"),
                    rs.getString("workspace_id"),
                    rs.getString("file_name"),
                    rs.getLong("file_size"),
                    rs.getString("mime_type"),
                    rs.getInt("chunk_size"),
                    rs.getInt("total_chunks"),
                    rs.getString("status"),
                    rs.getString("source_id"),
                    rs.getString("task_id"), rs.getString("expected_sha256"), rs.getTimestamp("updated_at").toInstant()
            );
        }, uploadId);
    }

    private UploadRow findUploadForUpdate(String uploadId) {
        return jdbcTemplate.query("""
                select id, workspace_id, file_name, file_size, mime_type, chunk_size, total_chunks, status, source_id, task_id, expected_sha256, updated_at
                from document_upload where id = ? for update
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("UPLOAD_NOT_FOUND", "上传事务不存在", HttpStatus.NOT_FOUND);
            }
            return new UploadRow(
                    rs.getString("id"),
                    rs.getString("workspace_id"),
                    rs.getString("file_name"),
                    rs.getLong("file_size"),
                    rs.getString("mime_type"),
                    rs.getInt("chunk_size"),
                    rs.getInt("total_chunks"),
                    rs.getString("status"),
                    rs.getString("source_id"),
                    rs.getString("task_id"), rs.getString("expected_sha256"), rs.getTimestamp("updated_at").toInstant()
            );
        }, uploadId);
    }

    private void verifyContentMd5(String contentMd5, byte[] content) {
        if (contentMd5 == null || contentMd5.isBlank()) {
            return;
        }
        String normalized = contentMd5.trim();
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(content);
            String hex = HexFormat.of().formatHex(digest);
            String base64 = Base64.getEncoder().encodeToString(digest);
            if (!normalized.equalsIgnoreCase(hex) && !normalized.equals(base64)) {
                throw new BusinessException("UPLOAD_CHUNK_MD5_MISMATCH", "分片 MD5 校验失败");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("MD5 not available", ex);
        }
    }

    private String sanitize(String fileName) {
        String normalized = fileName == null ? "source.txt" : fileName;
        return normalized.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private record UploadRow(
            String id,
            String workspaceId,
            String fileName,
            long fileSize,
            String mimeType,
            int chunkSize,
            int totalChunks,
            String status,
            String sourceId,
            String taskId,
            String expectedSha256,
            Instant updatedAt
    ) {
    }

    private record FileObjectRef(String id, String objectKey, long size) {
    }
}
