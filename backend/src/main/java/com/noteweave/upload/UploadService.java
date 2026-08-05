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
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);
    private static final String BUCKET_SOURCE = "noteweave-source";
    private static final String BUCKET_DERIVED = "noteweave-derived";

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
            SourceCatalogVersionService sourceCatalogVersionService
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
        log.info("UploadService initialised with object storage backend: {}", storage.backendName());
    }

    @Transactional
    public CreateUploadResponse createUpload(String workspaceId, CreateUploadRequest request) {
        uploadSecurityPolicy.validateMetadata(request);
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.SOURCE_WRITE);
        String actor = auditActorProvider.currentOrSystem("UPLOAD");
        String uploadId = Ids.newId();
        jdbcTemplate.update("""
                insert into document_upload(
                    id, workspace_id, file_name, file_size, mime_type, chunk_size, total_chunks, status,
                    created_by, updated_by
                ) values (?, ?, ?, ?, ?, ?, ?, 'UPLOADING', ?, ?)
                """, uploadId, workspaceId, request.fileName(), request.fileSize(), request.mimeType(),
                request.chunkSize(), request.totalChunks(), actor, actor);
        return new CreateUploadResponse(uploadId, "UPLOADING", request.chunkSize(), request.totalChunks());
    }

    @Transactional
    public UploadChunkResponse acceptChunk(String uploadId, int chunkIndex, String contentMd5, byte[] content) {
        UploadRow upload = findUpload(uploadId);
        workspaceAccessGuard.requirePermission(upload.workspaceId(), WorkspacePermission.SOURCE_WRITE);
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
        }
        return new UploadChunkResponse(uploadId, chunkIndex, true);
    }

    @Transactional
    public CompleteUploadResponse completeUpload(String uploadId) {
        UploadRow upload = findUploadForUpdate(uploadId);
        workspaceAccessGuard.requirePermission(upload.workspaceId(), WorkspacePermission.SOURCE_WRITE);
        String actor = auditActorProvider.currentOrSystem("UPLOAD");
        if ("COMPLETED".equals(upload.status()) && upload.sourceId() != null && upload.taskId() != null) {
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
                select c.chunk_index, c.object_key
                from upload_chunk c
                join document_upload u on u.id = c.upload_id
                where u.workspace_id = ? and c.upload_id = ?
                order by c.chunk_index
                """, upload.workspaceId(), uploadId);
        if (chunkRows.size() != upload.totalChunks()) {
            throw new BusinessException("UPLOAD_CHUNK_INCOMPLETE", "上传分片尚未完整");
        }
        byte[] merged = mergeChunks(chunkRows);
        uploadSecurityPolicy.validateMergedContent(upload.mimeType(), upload.fileSize(), merged);
        String sha256 = sha256(merged);
        FileObjectRef fileObject = getOrCreateFileObject(upload, sha256, merged.length, merged);
        String sourceId = Ids.newId();
        String snapshotId = Ids.newId();
        String objectKey = "workspace/%s/source/%s/snapshot/1/original/%s".formatted(upload.workspaceId(), sourceId, sanitize(upload.fileName()));
        storage.write(BUCKET_SOURCE, objectKey, merged);
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
            sourceParsePort.parseAndIndex(upload.workspaceId(), sourceId, snapshotId, merged);
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
        return new CompleteUploadResponse(sourceId, taskId, finalParseStatus, finalIndexStatus);
    }

    private CompleteUploadResponse sourceResult(String workspaceId, String sourceId, String taskId) {
        return jdbcTemplate.query("""
                select parse_status, index_status from source where workspace_id = ? and id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("SOURCE_NOT_FOUND", "资料不存在");
            }
            return new CompleteUploadResponse(sourceId, taskId, rs.getString("parse_status"), rs.getString("index_status"));
        }, workspaceId, sourceId);
    }

    private byte[] mergeChunks(List<Map<String, Object>> chunkRows) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (Map<String, Object> row : chunkRows) {
                String objectKey = (String) row.get("object_key");
                output.write(storage.read(BUCKET_SOURCE, objectKey));
            }
            return output.toByteArray();
        } catch (Exception ex) {
            throw new BusinessException("UPLOAD_MERGE_FAILED", "上传分片合并失败");
        }
    }

    private FileObjectRef getOrCreateFileObject(UploadRow upload, String sha256, long size, byte[] merged) {
        List<FileObjectRef> existing = jdbcTemplate.query("""
                select id, object_key from file_object where workspace_id = ? and sha256 = ?
                """, (rs, rowNum) -> new FileObjectRef(rs.getString("id"), rs.getString("object_key")), upload.workspaceId(), sha256);
        if (!existing.isEmpty()) {
            FileObjectRef ref = existing.get(0);
            String bucket = storage.backendName().equals("local") ? "noteweave-source" : ref.objectKey().contains("/") ? ref.objectKey().substring(0, ref.objectKey().indexOf('/')) : BUCKET_SOURCE;
            String key = storage.backendName().equals("local") ? ref.objectKey().substring(ref.objectKey().indexOf('/') + 1) : ref.objectKey();
            if (!storage.exists(bucket, key)) {
                storage.write(bucket, key, merged);
            }
            jdbcTemplate.update("""
                    update file_object set ref_count = ref_count + 1
                    where workspace_id = ? and id = ?
                    """, upload.workspaceId(), ref.id());
            return ref;
        }
        String fileObjectId = Ids.newId();
        String objectKey = "workspace/%s/file_object/%s-%s".formatted(upload.workspaceId(), sha256, sanitize(upload.fileName()));
        storage.write(BUCKET_SOURCE, objectKey, merged);
        jdbcTemplate.update("""
                insert into file_object(id, workspace_id, object_key, sha256, file_size, mime_type, ref_count)
                values (?, ?, ?, ?, ?, ?, 1)
                """, fileObjectId, upload.workspaceId(), objectKey, sha256, size, upload.mimeType());
        return new FileObjectRef(fileObjectId, objectKey);
    }

    private UploadRow findUpload(String uploadId) {
        return jdbcTemplate.query("""
                select id, workspace_id, file_name, file_size, mime_type, chunk_size, total_chunks, status, source_id, task_id
                from document_upload where id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("UPLOAD_NOT_FOUND", "上传事务不存在");
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
                    rs.getString("task_id")
            );
        }, uploadId);
    }

    private UploadRow findUploadForUpdate(String uploadId) {
        return jdbcTemplate.query("""
                select id, workspace_id, file_name, file_size, mime_type, chunk_size, total_chunks, status, source_id, task_id
                from document_upload where id = ? for update
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("UPLOAD_NOT_FOUND", "上传事务不存在");
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
                    rs.getString("task_id")
            );
        }, uploadId);
    }

    private String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
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
            String taskId
    ) {
    }

    private record FileObjectRef(String id, String objectKey) {
    }
}
