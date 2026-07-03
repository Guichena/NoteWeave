package com.noteweave.upload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.noteweave.infra.LocalObjectStorage;
import com.noteweave.source.SourceParseService;
import com.noteweave.task.TaskService;
import com.noteweave.workspace.WorkspaceService;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UploadService {

    private final JdbcTemplate jdbcTemplate;
    private final WorkspaceService workspaceService;
    private final LocalObjectStorage storage;
    private final SourceParseService sourceParseService;
    private final TaskService taskService;
    private final ObjectMapper objectMapper;

    public UploadService(
            JdbcTemplate jdbcTemplate,
            WorkspaceService workspaceService,
            LocalObjectStorage storage,
            SourceParseService sourceParseService,
            TaskService taskService,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.workspaceService = workspaceService;
        this.storage = storage;
        this.sourceParseService = sourceParseService;
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CreateUploadResponse createUpload(String workspaceId, CreateUploadRequest request) {
        if (!workspaceService.exists(workspaceId)) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在");
        }
        String uploadId = Ids.newId();
        jdbcTemplate.update("""
                insert into document_upload(id, workspace_id, file_name, file_size, mime_type, chunk_size, total_chunks, status)
                values (?, ?, ?, ?, ?, ?, ?, 'UPLOADING')
                """, uploadId, workspaceId, request.fileName(), request.fileSize(), request.mimeType(), request.chunkSize(), request.totalChunks());
        return new CreateUploadResponse(uploadId, "UPLOADING", request.chunkSize(), request.totalChunks());
    }

    @Transactional
    public UploadChunkResponse acceptChunk(String uploadId, int chunkIndex, String contentMd5, byte[] content) {
        UploadRow upload = findUpload(uploadId);
        if (!"UPLOADING".equals(upload.status())) {
            throw new BusinessException("UPLOAD_NOT_WRITABLE", "当前上传事务不可继续写入");
        }
        if (chunkIndex < 0 || chunkIndex >= upload.totalChunks()) {
            throw new BusinessException("UPLOAD_CHUNK_OUT_OF_RANGE", "分片序号超出范围");
        }
        String objectKey = "workspace/%s/upload_tmp/%s/%d".formatted(upload.workspaceId(), uploadId, chunkIndex);
        storage.write(objectKey, content);
        int updated = jdbcTemplate.update("""
                update upload_chunk
                set content_md5 = ?, object_key = ?, byte_size = ?, created_at = current_timestamp
                where upload_id = ? and chunk_index = ?
                """, contentMd5, objectKey, content.length, uploadId, chunkIndex);
        if (updated == 0) {
            jdbcTemplate.update("""
                    insert into upload_chunk(id, upload_id, chunk_index, content_md5, object_key, byte_size)
                    values (?, ?, ?, ?, ?, ?)
                    """, Ids.newId(), uploadId, chunkIndex, contentMd5, objectKey, content.length);
            jdbcTemplate.update("""
                    update document_upload
                    set uploaded_chunks = uploaded_chunks + 1, updated_at = current_timestamp
                    where id = ?
                    """, uploadId);
        }
        return new UploadChunkResponse(uploadId, chunkIndex, true);
    }

    @Transactional
    public CompleteUploadResponse completeUpload(String uploadId) {
        UploadRow upload = findUpload(uploadId);
        if ("COMPLETED".equals(upload.status()) && upload.sourceId() != null && upload.taskId() != null) {
            return sourceResult(upload.sourceId(), upload.taskId());
        }
        List<Map<String, Object>> chunkRows = jdbcTemplate.queryForList("""
                select chunk_index, object_key from upload_chunk where upload_id = ? order by chunk_index
                """, uploadId);
        if (chunkRows.size() != upload.totalChunks()) {
            throw new BusinessException("UPLOAD_CHUNK_INCOMPLETE", "上传分片尚未完整");
        }
        byte[] merged = mergeChunks(chunkRows);
        String sha256 = sha256(merged);
        FileObjectRef fileObject = getOrCreateFileObject(upload, sha256, merged.length, merged);
        String sourceId = Ids.newId();
        String snapshotId = Ids.newId();
        String objectKey = "workspace/%s/source/%s/snapshot/1/original/%s".formatted(upload.workspaceId(), sourceId, sanitize(upload.fileName()));
        storage.write(objectKey, merged);
        jdbcTemplate.update("""
                insert into source(id, workspace_id, file_object_id, title, source_type, status, parse_status, index_status)
                values (?, ?, ?, ?, 'USER_UPLOAD', 'PROCESSING', 'PENDING', 'PENDING')
                """, sourceId, upload.workspaceId(), fileObject.id(), upload.fileName());
        jdbcTemplate.update("""
                insert into source_snapshot(id, source_id, file_object_id, version_no, object_key, sha256, parse_status, index_status)
                values (?, ?, ?, 1, ?, ?, 'PENDING', 'PENDING')
                """, snapshotId, sourceId, fileObject.id(), objectKey, sha256);
        String taskId = taskService.createTask(upload.workspaceId(), "SOURCE_PARSE", "SOURCE", sourceId, "PARSING", "资料解析与切片");
        jdbcTemplate.update("""
                insert into task_outbox(id, task_id, topic, message_key, payload_json, status)
                values (?, ?, 'noteweave.source.parse', ?, ?, 'READY')
                """, Ids.newId(), taskId, sourceId, Json.write(objectMapper, Map.of(
                "taskId", taskId,
                "sourceId", sourceId,
                "snapshotId", snapshotId,
                "workspaceId", upload.workspaceId()
        )));

        sourceParseService.parseAndIndex(upload.workspaceId(), sourceId, snapshotId, merged);
        taskService.completeTask(taskId, "INDEXED", "资料已经解析并写入本地检索切片", sourceId);
        jdbcTemplate.update("""
                update document_upload
                set status = 'COMPLETED', source_id = ?, task_id = ?, updated_at = current_timestamp
                where id = ?
                """, sourceId, taskId, uploadId);
        return new CompleteUploadResponse(sourceId, taskId, "PARSED", "INDEXED");
    }

    private CompleteUploadResponse sourceResult(String sourceId, String taskId) {
        return jdbcTemplate.query("""
                select parse_status, index_status from source where id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("SOURCE_NOT_FOUND", "资料不存在");
            }
            return new CompleteUploadResponse(sourceId, taskId, rs.getString("parse_status"), rs.getString("index_status"));
        }, sourceId);
    }

    private byte[] mergeChunks(List<Map<String, Object>> chunkRows) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (Map<String, Object> row : chunkRows) {
                output.write(storage.read((String) row.get("object_key")));
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
            if (!storage.exists(ref.objectKey())) {
                storage.write(ref.objectKey(), merged);
            }
            jdbcTemplate.update("update file_object set ref_count = ref_count + 1 where id = ?", ref.id());
            return ref;
        }
        String fileObjectId = Ids.newId();
        String objectKey = "workspace/%s/file_object/%s-%s".formatted(upload.workspaceId(), sha256, sanitize(upload.fileName()));
        storage.write(objectKey, merged);
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

    private String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
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
