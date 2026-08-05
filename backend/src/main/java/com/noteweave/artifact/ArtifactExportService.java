package com.noteweave.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.common.SensitiveErrorMessageSanitizer;
import com.noteweave.storage.ObjectStorage;
import com.noteweave.worker.ArtifactWorkerRestClientFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ArtifactExportService {

    private static final String MARKDOWN = "MARKDOWN";
    private static final String PDF = "PDF";
    private static final Logger log = LoggerFactory.getLogger(ArtifactExportService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ObjectStorage objectStorage;
    private final RestClient artifactWorkerClient;
    private final String exportBucket;

    public ArtifactExportService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ObjectStorage objectStorage,
            NoteWeaveProperties properties,
            @Value("${noteweave.internal.auth-token:}") String internalAuthToken,
            @Value("${noteweave.worker.connect-timeout-seconds:3}") long connectTimeoutSeconds,
            @Value("${noteweave.worker.read-timeout-seconds:30}") long readTimeoutSeconds
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.objectStorage = objectStorage;
        this.artifactWorkerClient = ArtifactWorkerRestClientFactory.create(
                properties.worker().artifactBaseUrl(),
                internalAuthToken,
                connectTimeoutSeconds,
                readTimeoutSeconds
        );
        this.exportBucket = properties.storage().minio().bucketExport();
    }

    @Transactional
    public List<ArtifactFileMetadataResponse> materializeExports(String artifactVersionId) {
        ExportRow row = loadVersionById(artifactVersionId);
        persistFile(
                row,
                MARKDOWN,
                safeStem(row.title()) + ".md",
                "text/markdown; charset=UTF-8",
                row.contentMarkdown().getBytes(StandardCharsets.UTF_8)
        );
        String pdfFileName = readCompiledFileNameIfPresent(row.resultPayloadJson());
        if (!pdfFileName.isBlank()) {
            persistWorkerPdf(row, pdfFileName);
        }
        return listFiles(artifactVersionId);
    }

    public ArtifactExportFile downloadPdf(String workspaceId, String artifactJobId, int versionNo) {
        ExportRow row = loadVersion(workspaceId, artifactJobId, versionNo);
        if (!hasFile(row.versionId(), PDF)) {
            materializeExports(row.versionId());
        }
        StoredFile file = loadStoredFile(row.versionId(), PDF);
        byte[] content = objectStorage.read(file.bucketName(), file.objectKey());
        if (content.length == 0) {
            throw new BusinessException("ARTIFACT_EXPORT_EMPTY", "产物 PDF 文件为空");
        }
        return new ArtifactExportFile(file.fileName(), content);
    }

    public List<ArtifactFileMetadataResponse> listFiles(String artifactVersionId) {
        return jdbcTemplate.query("""
                select id, file_format, file_name, media_type, storage_backend, bucket_name,
                       object_key, size_bytes, checksum_sha256, status, created_at
                       , coalesce(error_message, '') as error_message
                from artifact_file where artifact_version_id = ?
                order by file_format asc, created_at asc
                """, (rs, rowNum) -> new ArtifactFileMetadataResponse(
                rs.getString("id"),
                rs.getString("file_format"),
                rs.getString("file_name"),
                rs.getString("media_type"),
                rs.getString("storage_backend"),
                rs.getString("bucket_name"),
                rs.getString("object_key"),
                rs.getLong("size_bytes"),
                rs.getString("checksum_sha256"),
                rs.getString("status"),
                rs.getString("error_message"),
                rs.getTimestamp("created_at").toInstant()
        ), artifactVersionId);
    }

    @Transactional
    public List<ArtifactFileMetadataResponse> copyFiles(String sourceVersionId, String targetVersionId) {
        materializeExports(sourceVersionId);
        ExportRow target = loadVersionById(targetVersionId);
        List<StoredSourceFile> sourceFiles = jdbcTemplate.query("""
                select file_format, file_name, media_type, bucket_name, object_key
                from artifact_file
                where artifact_version_id = ? and status = 'READY'
                order by file_format
                """, (rs, rowNum) -> new StoredSourceFile(
                rs.getString("file_format"),
                rs.getString("file_name"),
                rs.getString("media_type"),
                rs.getString("bucket_name"),
                rs.getString("object_key")
        ), sourceVersionId);
        for (StoredSourceFile source : sourceFiles) {
            persistFile(
                    target,
                    source.fileFormat(),
                    source.fileName(),
                    source.mediaType(),
                    objectStorage.read(source.bucketName(), source.objectKey())
            );
        }
        return listFiles(targetVersionId);
    }

    private void persistFile(ExportRow row, String format, String fileName, String mediaType, byte[] content) {
        if (hasFile(row.versionId(), format)) {
            return;
        }
        String objectKey = "artifacts/%s/v%d/%s".formatted(
                row.artifactJobId(), row.versionNo(), safeFileName(fileName)
        );
        if (content == null || content.length == 0) {
            upsertFileMetadata(
                    row, format, fileName, mediaType, objectKey, 0, "", "FAILED", "产物导出文件为空"
            );
            return;
        }
        try {
            objectStorage.write(exportBucket, objectKey, content);
            upsertFileMetadata(row, format, fileName, mediaType, objectKey, content.length, sha256(content), "READY", "");
        } catch (RuntimeException ex) {
            upsertFileMetadata(
                    row, format, fileName, mediaType, objectKey, 0, "", "FAILED",
                    SensitiveErrorMessageSanitizer.sanitize(ex.getMessage())
            );
            log.warn("Artifact file materialization failed; versionId={}, format={}, error={}",
                    row.versionId(), format, ex.getMessage());
        }
    }

    private void persistWorkerPdf(ExportRow row, String fileName) {
        try {
            persistFile(row, PDF, fileName, "application/pdf", fetchWorkerExport(row.originTaskId(), fileName));
        } catch (RuntimeException ex) {
            String objectKey = "artifacts/%s/v%d/%s".formatted(
                    row.artifactJobId(), row.versionNo(), safeFileName(fileName)
            );
            upsertFileMetadata(
                    row, PDF, fileName, "application/pdf", objectKey, 0, "", "FAILED",
                    SensitiveErrorMessageSanitizer.sanitize(ex.getMessage())
            );
            log.warn("Artifact PDF acquisition failed; versionId={}, taskId={}, error={}",
                    row.versionId(), row.originTaskId(), ex.getMessage());
        }
    }

    private void upsertFileMetadata(
            ExportRow row,
            String format,
            String fileName,
            String mediaType,
            String objectKey,
            long sizeBytes,
            String checksum,
            String status,
            String errorMessage
    ) {
        int updated = jdbcTemplate.update("""
                update artifact_file
                set file_name = ?, media_type = ?, storage_backend = ?, bucket_name = ?, object_key = ?,
                    size_bytes = ?, checksum_sha256 = ?, status = ?, error_message = ?
                where artifact_version_id = ? and file_format = ?
                """,
                fileName, mediaType, objectStorage.backendName(), exportBucket, objectKey,
                sizeBytes, checksum, status, errorMessage, row.versionId(), format);
        if (updated == 0) {
            jdbcTemplate.update("""
                    insert into artifact_file(
                        id, artifact_version_id, file_format, file_name, media_type, storage_backend,
                        bucket_name, object_key, size_bytes, checksum_sha256, status, error_message
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    Ids.newId(), row.versionId(), format, fileName, mediaType, objectStorage.backendName(),
                    exportBucket, objectKey, sizeBytes, checksum, status, errorMessage);
        }
    }

    private byte[] fetchWorkerExport(String taskId, String fileName) {
        if (taskId == null || taskId.isBlank()) {
            throw new BusinessException("ARTIFACT_EXPORT_TASK_MISSING", "产物版本缺少来源任务，无法归档 PDF");
        }
        try {
            byte[] content = artifactWorkerClient.get()
                    .uri("/tasks/{taskId}/exports/{fileName}", taskId, fileName)
                    .retrieve()
                    .body(byte[].class);
            if (content == null || content.length == 0) {
                throw new BusinessException("ARTIFACT_EXPORT_EMPTY", "Artifact Worker 返回空 PDF");
            }
            return content;
        } catch (RestClientException ex) {
            throw new BusinessException(
                    "ARTIFACT_EXPORT_FETCH_FAILED",
                    "无法从 Artifact Worker 获取 PDF 文件",
                    HttpStatus.BAD_GATEWAY
            );
        }
    }

    private ExportRow loadVersion(String workspaceId, String artifactJobId, int versionNo) {
        List<ExportRow> rows = jdbcTemplate.query("""
                select av.id, av.artifact_job_id, av.version_no, av.title, av.content_markdown,
                       av.result_payload_json, coalesce(av.origin_task_id, aj.task_id) as origin_task_id
                from artifact_version av
                join artifact_job aj on aj.id = av.artifact_job_id
                where aj.workspace_id = ? and aj.id = ? and av.version_no = ?
                """, (rs, rowNum) -> mapExportRow(rs), workspaceId, artifactJobId, versionNo);
        if (rows.isEmpty()) {
            throw new BusinessException("ARTIFACT_VERSION_NOT_FOUND", "产物版本不存在", HttpStatus.NOT_FOUND);
        }
        return rows.get(0);
    }

    private ExportRow loadVersionById(String artifactVersionId) {
        List<ExportRow> rows = jdbcTemplate.query("""
                select av.id, av.artifact_job_id, av.version_no, av.title, av.content_markdown,
                       av.result_payload_json, coalesce(av.origin_task_id, aj.task_id) as origin_task_id
                from artifact_version av
                join artifact_job aj on aj.id = av.artifact_job_id
                where av.id = ?
                """, (rs, rowNum) -> mapExportRow(rs), artifactVersionId);
        if (rows.isEmpty()) {
            throw new BusinessException("ARTIFACT_VERSION_NOT_FOUND", "产物版本不存在", HttpStatus.NOT_FOUND);
        }
        return rows.get(0);
    }

    private ExportRow mapExportRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ExportRow(
                rs.getString("id"),
                rs.getString("artifact_job_id"),
                rs.getInt("version_no"),
                rs.getString("title"),
                rs.getString("content_markdown") == null ? "" : rs.getString("content_markdown"),
                rs.getString("result_payload_json") == null ? "{}" : rs.getString("result_payload_json"),
                rs.getString("origin_task_id")
        );
    }

    private boolean hasFile(String versionId, String format) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from artifact_file where artifact_version_id = ? and file_format = ? and status = 'READY'",
                Integer.class,
                versionId,
                format
        );
        return count != null && count > 0;
    }

    private StoredFile loadStoredFile(String versionId, String format) {
        List<StoredFile> rows = jdbcTemplate.query("""
                select file_name, bucket_name, object_key
                from artifact_file
                where artifact_version_id = ? and file_format = ? and status = 'READY'
                """, (rs, rowNum) -> new StoredFile(
                rs.getString("file_name"), rs.getString("bucket_name"), rs.getString("object_key")
        ), versionId, format);
        if (rows.isEmpty()) {
            throw new BusinessException("ARTIFACT_EXPORT_NOT_READY", "该产物版本没有可下载的 PDF", HttpStatus.CONFLICT);
        }
        return rows.get(0);
    }

    private String readCompiledFileNameIfPresent(String payloadJson) {
        try {
            JsonNode exportTrace = objectMapper.readTree(payloadJson).path("export_trace");
            if (!"COMPILED".equals(exportTrace.path("status").asText())) {
                return "";
            }
            String fileName = exportTrace.path("file_name").asText().trim();
            if (fileName.isEmpty() || !fileName.toLowerCase().endsWith(".pdf")
                    || fileName.contains("/") || fileName.contains("\\")) {
                throw new BusinessException("ARTIFACT_EXPORT_INVALID", "产物 PDF 文件名无效");
            }
            return fileName;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("ARTIFACT_EXPORT_TRACE_INVALID", "产物 PDF 导出信息解析失败");
        }
    }

    private String safeStem(String title) {
        String stem = title == null ? "artifact" : title.trim().replaceAll("[\\\\/:*?\"<>|]", "-");
        return stem.isBlank() ? "artifact" : stem.substring(0, Math.min(stem.length(), 120));
    }

    private String safeFileName(String fileName) {
        String safe = fileName.replaceAll("[\\\\/:*?\"<>|]", "-");
        if (safe.isBlank() || safe.equals(".") || safe.equals("..")) {
            throw new BusinessException("ARTIFACT_EXPORT_INVALID", "产物文件名无效");
        }
        return safe;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String abbreviate(String value, int maxLength) {
        String normalized = value == null || value.isBlank() ? "artifact file materialization failed" : value;
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private record ExportRow(
            String versionId,
            String artifactJobId,
            int versionNo,
            String title,
            String contentMarkdown,
            String resultPayloadJson,
            String originTaskId
    ) {
    }

    private record StoredFile(String fileName, String bucketName, String objectKey) {
    }

    private record StoredSourceFile(
            String fileFormat,
            String fileName,
            String mediaType,
            String bucketName,
            String objectKey
    ) {
    }
}
