package com.noteweave.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.noteweave.common.SensitiveErrorMessageSanitizer;
import com.noteweave.storage.ObjectStorage;
import com.noteweave.worker.WorkerCompleteRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.scheduling.annotation.Scheduled;
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
    private final ArtifactWorkerExportClient artifactWorkerClient;
    private final ArtifactSkillCatalogService skillCatalog;
    private final String exportBucket;
    private String orphanScanCursor = "";

    public ArtifactExportService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ObjectStorage objectStorage,
            ArtifactWorkerExportClient artifactWorkerClient,
            ArtifactSkillCatalogService skillCatalog,
            com.noteweave.config.NoteWeaveProperties properties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.objectStorage = objectStorage;
        this.artifactWorkerClient = artifactWorkerClient;
        this.skillCatalog = skillCatalog;
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
        requireVersionSourcesVisible(row);
        requireReadyDelivery(row.versionId());
        if (!hasFile(row.versionId(), PDF)) {
            Integer published = jdbcTemplate.queryForObject(
                    "select count(*) from artifact_candidate_receipt where artifact_version_id = ?",
                    Integer.class, row.versionId());
            if (published != null && published > 0) {
                throw new BusinessException("ARTIFACT_EXPORT_NOT_READY", "已发布版本缺少 PDF 文件", HttpStatus.CONFLICT);
            }
            materializeExports(row.versionId()); // historical compatibility only
        }
        StoredFile file = loadStoredFile(row.versionId(), PDF);
        byte[] content;
        try {
            content = objectStorage.read(file.bucketName(), file.objectKey());
        } catch (RuntimeException ex) {
            jdbcTemplate.update("update artifact_version set delivery_status = 'DEGRADED' where id = ?",
                    row.versionId());
            throw new BusinessException("ARTIFACT_EXPORT_DEGRADED", "产物 PDF 文件暂不可用", HttpStatus.CONFLICT);
        }
        if (content.length == 0 || !sha256(content).equals(file.checksum())) {
            jdbcTemplate.update("update artifact_version set delivery_status = 'DEGRADED' where id = ?",
                    row.versionId());
            throw new BusinessException("ARTIFACT_EXPORT_DEGRADED", "产物 PDF 文件摘要不匹配", HttpStatus.CONFLICT);
        }
        return new ArtifactExportFile(file.fileName(), content);
    }

    /** Fetches and checks every required payload before the Version transaction publishes it. */
    public List<PreparedFile> prepareForCompletion(String taskId, WorkerCompleteRequest request) {
        String markdown = request.resultPayload() == null ? ""
                : String.valueOf(request.resultPayload().getOrDefault("markdown", ""));
        if (markdown.isBlank()) {
            throw new BusinessException("ARTIFACT_CONTENT_EMPTY", "产物正文为空", HttpStatus.CONFLICT);
        }
        java.util.ArrayList<PreparedFile> files = new java.util.ArrayList<>();
        byte[] markdownBytes = markdown.getBytes(StandardCharsets.UTF_8);
        files.add(new PreparedFile(MARKDOWN, safeStem(request.resultTitle()) + ".md",
                "text/markdown; charset=UTF-8", markdownBytes, sha256(markdownBytes)));
        String skillKey = jdbcTemplate.queryForObject("""
                select aj.skill_key from artifact_job_run r
                join artifact_job aj on aj.id = r.artifact_job_id
                where r.task_id = ?
                """, String.class, taskId);
        String payloadJson = Json.write(objectMapper, request.resultPayload());
        String pdfFileName = readCompiledFileNameIfPresent(payloadJson);
        boolean pdfRequired = skillCatalog.requiredFileRoles(skillKey).contains("PRIMARY_PDF");
        if (pdfRequired && pdfFileName.isBlank()) {
            throw new BusinessException("ARTIFACT_REQUIRED_FILE_MISSING",
                    "PDF 讲义缺少已编译文件", HttpStatus.CONFLICT);
        }
        if (!pdfRequired && !pdfFileName.isBlank()) {
            throw new BusinessException("ARTIFACT_FILE_ROLE_INVALID",
                    "当前 Skill 不允许交付 PDF 文件", HttpStatus.CONFLICT);
        }
        if (!pdfFileName.isBlank()) {
            byte[] pdf = fetchWorkerExport(taskId, pdfFileName);
            verifyPdf(pdf);
            files.add(new PreparedFile(PDF, pdfFileName, "application/pdf", pdf, sha256(pdf)));
        }
        appendDeclaredAuxiliaryFiles(taskId, request, files);
        requireCandidateFileManifest(request, files);
        if ("video_learning_deck".equals(skillKey)) {
            Object deckIr = request.resultPayload().get("video_deck_ir");
            if (!(deckIr instanceof Map<?, ?> ir) || !(ir.get("slides") instanceof List<?> slides)) {
                throw invalidManifest();
            }
            List<PreparedFile> decks = files.stream()
                    .filter(file -> "PRIMARY_PPTX".equals(file.role())).toList();
            long previews = files.stream()
                    .filter(file -> "SLIDE_PREVIEW".equals(file.role())).count();
            if (decks.size() != 1 || previews != slides.size()) throw invalidManifest();
            VideoDeckFileVerifier.validate(decks.get(0).content(), slides);
        }
        return List.copyOf(files);
    }

    private void appendDeclaredAuxiliaryFiles(String taskId, WorkerCompleteRequest request,
                                              List<PreparedFile> files) {
        Object candidate = request.resultPayload() == null ? null : request.resultPayload().get("candidate");
        if (!(candidate instanceof Map<?, ?> envelope)) return;
        if (!(envelope.get("required_files") instanceof List<?> declared) || declared.size() > 501) {
            throw invalidManifest();
        }
        for (Object item : declared) {
            if (!(item instanceof Map<?, ?> entry)) throw invalidManifest();
            String role = String.valueOf(entry.get("role"));
            if ("PRIMARY_MARKDOWN".equals(role) || "PRIMARY_PDF".equals(role)) continue;
            String format = switch (role) {
                case "PRIMARY_PPTX" -> "PPTX";
                case "SOURCE_MD" -> MARKDOWN;
                case "SLIDE_PREVIEW" -> "PNG";
                default -> throw invalidManifest();
            };
            String mediaType = switch (format) {
                case "PPTX" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
                case "PNG" -> "image/png";
                default -> "text/markdown; charset=UTF-8";
            };
            String extension = switch (format) {
                case "PPTX" -> ".pptx";
                case "PNG" -> ".png";
                default -> ".md";
            };
            String name = String.valueOf(entry.get("file_name"));
            if (name.length() > 160 || !name.toLowerCase(java.util.Locale.ROOT).endsWith(extension)
                    || !name.equals(safeFileName(name)) || name.contains("..")
                    || name.chars().anyMatch(character -> character < 32)) {
                throw invalidManifest();
            }
            if (!mediaType.equals(entry.get("media_type"))
                    || !(entry.get("variant") instanceof String variant)
                    || !(entry.get("sequence_no") instanceof Number sequence)
                    || !isIntegralNumber(sequence) || sequence.longValue() < 0 || sequence.longValue() > 10_000) {
                throw invalidManifest();
            }
            byte[] content = fetchWorkerExport(taskId, name);
            if (content.length == 0 || content.length > 100_000_000) throw invalidManifest();
            verifyPreparedFormat(format, content);
            files.add(new PreparedFile(format, role, variant, sequence.intValue(), name,
                    mediaType, content, sha256(content)));
        }
    }

    private void requireCandidateFileManifest(WorkerCompleteRequest request, List<PreparedFile> files) {
        Object candidate = request.resultPayload() == null ? null : request.resultPayload().get("candidate");
        if (candidate == null) return; // Historical Worker callbacks did not carry a manifest.
        if (!(candidate instanceof Map<?, ?> envelope)
                || !(envelope.get("required_files") instanceof List<?> declared)
                || declared.size() != files.size()) {
            throw invalidManifest();
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Object item : declared) {
            if (!(item instanceof Map<?, ?> entry)) throw invalidManifest();
            String role = String.valueOf(entry.get("role"));
            String variant = String.valueOf(entry.get("variant"));
            if (!isIntegralNumber(entry.get("sequence_no"))) throw invalidManifest();
            int sequenceNo = ((Number) entry.get("sequence_no")).intValue();
            if (!seen.add(role + ":" + variant + ":" + sequenceNo)) throw invalidManifest();
            PreparedFile actual = files.stream()
                    .filter(file -> file.role().equals(role) && file.variant().equals(variant)
                            && file.sequenceNo() == sequenceNo).findFirst().orElse(null);
            if (actual == null || !actual.checksum().equals(entry.get("checksum_sha256"))
                    || !actual.mediaType().equals(entry.get("media_type"))
                    || !isIntegralNumber(entry.get("size_bytes"))
                    || !(entry.get("size_bytes") instanceof Number size)
                    || size.longValue() != actual.content().length
                    || !(entry.get("variant") instanceof String)
                    || (PDF.equals(actual.format()) && !actual.fileName().equals(entry.get("file_name")))
                    || (!"PRIMARY_MARKDOWN".equals(role)
                            && !actual.fileName().equals(entry.get("file_name")))) {
                throw invalidManifest();
            }
        }
    }

    private BusinessException invalidManifest() {
        return new BusinessException("ARTIFACT_FILE_MANIFEST_INVALID",
                "Candidate 文件清单与实际交付文件不一致", HttpStatus.CONFLICT);
    }

    private boolean isIntegralNumber(Object value) {
        return value instanceof Integer || value instanceof Long;
    }

    public List<PreparedFile> prepareRollbackFiles(String sourceVersionId) {
        materializeExports(sourceVersionId); // lazily archive historical files before copying
        ExportRow source = loadVersionById(sourceVersionId);
        Integer unavailable = jdbcTemplate.queryForObject("""
                select count(*) from artifact_file
                where artifact_version_id = ? and status <> 'READY'
                """, Integer.class, sourceVersionId);
        if (unavailable != null && unavailable > 0) {
            throw new BusinessException("ARTIFACT_SOURCE_FILE_MISSING",
                    "回滚源版本包含未就绪文件", HttpStatus.CONFLICT);
        }
        List<PreparedFile> files = jdbcTemplate.query("""
                select file_format, file_role, variant, sequence_no,
                       file_name, media_type, bucket_name, object_key, checksum_sha256
                from artifact_file where artifact_version_id = ? and status = 'READY'
                order by file_role, variant, sequence_no
                """, (rs, index) -> {
            byte[] bytes = objectStorage.read(rs.getString("bucket_name"), rs.getString("object_key"));
            String checksum = sha256(bytes);
            if (bytes.length == 0 || !checksum.equals(rs.getString("checksum_sha256"))) {
                throw new BusinessException("ARTIFACT_SOURCE_FILE_INVALID",
                        "回滚源文件摘要不匹配", HttpStatus.CONFLICT);
            }
            verifyPreparedFormat(rs.getString("file_format"), bytes);
            return new PreparedFile(rs.getString("file_format"), rs.getString("file_role"),
                    rs.getString("variant"), rs.getInt("sequence_no"), rs.getString("file_name"),
                    rs.getString("media_type"), bytes, checksum);
        }, sourceVersionId);
        boolean markdownReady = files.stream().anyMatch(file -> MARKDOWN.equals(file.format()));
        boolean pdfRequired = !readCompiledFileNameIfPresent(source.resultPayloadJson()).isBlank();
        boolean pdfReady = files.stream().anyMatch(file -> PDF.equals(file.format()));
        if (!markdownReady || (pdfRequired && !pdfReady)) {
            throw new BusinessException("ARTIFACT_SOURCE_FILE_MISSING",
                    "回滚源版本缺少必需文件", HttpStatus.CONFLICT);
        }
        return files;
    }

    public void publishPreparedFiles(String versionId, List<PreparedFile> files) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Artifact files must publish in the Version transaction");
        }
        ExportRow row = loadVersionById(versionId);
        String skillKey = jdbcTemplate.queryForObject(
                "select skill_key from artifact_version where id = ?", String.class, versionId);
        java.util.Set<String> presentRoles = new java.util.HashSet<>(jdbcTemplate.queryForList(
                "select file_role from artifact_file where artifact_version_id = ? and status = 'READY'",
                String.class, versionId));
        for (PreparedFile file : files) presentRoles.add(file.role());
        if (!presentRoles.containsAll(skillCatalog.requiredFileRoles(skillKey))) {
            throw new BusinessException("ARTIFACT_REQUIRED_FILE_MISSING",
                    "已发布 Skill 的必需文件角色不齐", HttpStatus.CONFLICT);
        }
        java.util.Set<String> fileKeys = new java.util.HashSet<>();
        for (PreparedFile file : files) {
            String key = file.role() + ":" + file.variant() + ":" + file.sequenceNo();
            if (!fileKeys.add(key) || !java.util.Set.of(
                    "PRIMARY_MARKDOWN", "PRIMARY_PDF", "PRIMARY_PPTX", "SOURCE_MD", "SLIDE_PREVIEW"
            ).contains(file.role()) || file.variant() == null
                    || !file.variant().matches("[A-Za-z0-9_-]{0,64}")
                    || file.sequenceNo() < 0 || file.sequenceNo() > 10_000) {
                throw new BusinessException("ARTIFACT_FILE_ROLE_INVALID",
                        "产物文件角色或序号无效", HttpStatus.CONFLICT);
            }
            if (!java.util.Map.of(
                    "PRIMARY_MARKDOWN", MARKDOWN,
                    "PRIMARY_PDF", PDF,
                    "PRIMARY_PPTX", "PPTX",
                    "SOURCE_MD", MARKDOWN,
                    "SLIDE_PREVIEW", "PNG"
            ).get(file.role()).equals(file.format())) {
                throw new BusinessException("ARTIFACT_FILE_ROLE_INVALID",
                        "文件角色与格式不匹配", HttpStatus.CONFLICT);
            }
            if (file.content().length == 0 || !sha256(file.content()).equals(file.checksum())) {
                throw new BusinessException("ARTIFACT_FILE_INVALID", "发布文件摘要不匹配", HttpStatus.CONFLICT);
            }
            verifyPreparedFormat(file.format(), file.content());
            String objectKey = "artifacts/staged/%s/%s/%s/%s/%d/%s".formatted(
                    row.originTaskId(), versionId, file.role(),
                    file.variant().isEmpty() ? "default" : file.variant(),
                    file.sequenceNo(), safeFileName(file.fileName()));
            objectStorage.write(exportBucket, objectKey, file.content());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        try {
                            objectStorage.delete(exportBucket, objectKey);
                        } catch (RuntimeException ex) {
                            log.warn("Artifact staged file cleanup failed; key={}", objectKey, ex);
                        }
                    }
                }
            });
            byte[] stored = objectStorage.read(exportBucket, objectKey);
            if (!file.checksum().equals(sha256(stored))) {
                throw new BusinessException("ARTIFACT_FILE_STORE_MISMATCH",
                        "暂存文件写入后摘要不匹配", HttpStatus.CONFLICT);
            }
            upsertFileMetadata(row, file.format(), file.role(), file.variant(), file.sequenceNo(),
                    file.fileName(), file.mediaType(), objectKey,
                    file.content().length, file.checksum(), "READY", "");
        }
    }

    private void verifyPdf(byte[] pdf) {
        if (pdf.length < 16 || pdf.length > 100_000_000
                || !new String(pdf, 0, Math.min(pdf.length, 8), StandardCharsets.US_ASCII).startsWith("%PDF-")
                || !new String(pdf, Math.max(0, pdf.length - 2048), Math.min(pdf.length, 2048),
                    StandardCharsets.US_ASCII).contains("%%EOF")) {
            throw new BusinessException("ARTIFACT_PDF_INVALID", "PDF 文件签名或结尾无效", HttpStatus.CONFLICT);
        }
        try (PDDocument document = PDDocument.load(pdf)) {
            int pages = document.getNumberOfPages();
            if (pages < 1 || pages > 500) {
                throw new BusinessException("ARTIFACT_PDF_INVALID", "PDF 页数无效", HttpStatus.CONFLICT);
            }
            PDFRenderer renderer = new PDFRenderer(document);
            renderer.renderImageWithDPI(0, 36);
            if (pages > 1) renderer.renderImageWithDPI(pages - 1, 36);
        } catch (java.io.IOException ex) {
            throw new BusinessException("ARTIFACT_PDF_INVALID", "PDF 无法打开或渲染", HttpStatus.CONFLICT);
        }
    }

    private void verifyPreparedFormat(String format, byte[] content) {
        if (PDF.equals(format)) {
            verifyPdf(content);
        } else if ("PNG".equals(format)) {
            try {
                java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(
                        new java.io.ByteArrayInputStream(content));
                if (image == null || image.getWidth() < 1 || image.getHeight() < 1
                        || (long) image.getWidth() * image.getHeight() > 50_000_000) {
                    throw new java.io.IOException("invalid PNG dimensions");
                }
            } catch (java.io.IOException ex) {
                throw new BusinessException("ARTIFACT_PNG_INVALID", "PNG 预览不可打开", HttpStatus.CONFLICT);
            }
        } else if ("PPTX".equals(format)) {
            boolean contentTypes = false;
            boolean presentation = false;
            int slides = 0;
            try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                    new java.io.ByteArrayInputStream(content))) {
                java.util.zip.ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if ("[Content_Types].xml".equals(name)) contentTypes = true;
                    if ("ppt/presentation.xml".equals(name)) presentation = true;
                    if (name.matches("ppt/slides/slide[0-9]+\\.xml")) slides++;
                    if (slides > 500) break;
                }
            } catch (java.io.IOException ex) {
                throw new BusinessException("ARTIFACT_PPTX_INVALID", "PPTX 无法打开", HttpStatus.CONFLICT);
            }
            if (!contentTypes || !presentation || slides < 1 || slides > 500) {
                throw new BusinessException("ARTIFACT_PPTX_INVALID", "PPTX 缺少演示文稿或页面结构", HttpStatus.CONFLICT);
            }
        }
    }

    public record PreparedFile(String format, String role, String variant, int sequenceNo,
                               String fileName, String mediaType, byte[] content, String checksum) {
        public PreparedFile(String format, String fileName, String mediaType,
                            byte[] content, String checksum) {
            this(format, primaryRole(format), "", 0, fileName, mediaType, content, checksum);
        }
    }

    /** Reclaims only old staged objects that no committed file metadata references. */
    @Scheduled(fixedDelayString = "${noteweave.artifact.orphan-cleanup-delay-ms:3600000}")
    public synchronized int cleanupOrphanedStagedFiles() {
        String prefix = "artifacts/staged/";
        Instant cutoff = Instant.now().minus(java.time.Duration.ofHours(24));
        int removed = 0;
        int visited = 0;
        while (visited < 1_000) {
            List<ObjectStorage.StoredObject> page = objectStorage.list(exportBucket, prefix, orphanScanCursor, 100);
            if (page.isEmpty()) {
                orphanScanCursor = "";
                break;
            }
            for (ObjectStorage.StoredObject object : page) {
                orphanScanCursor = object.key();
                visited++;
                if (!object.key().startsWith(prefix) || object.lastModified() == null
                        || !object.lastModified().isBefore(cutoff)) continue;
                Integer references = jdbcTemplate.queryForObject("""
                        select count(*) from artifact_file where bucket_name = ? and object_key = ?
                        """, Integer.class, exportBucket, object.key());
                if (references != null && references > 0) continue;
                try {
                    objectStorage.delete(exportBucket, object.key());
                    removed++;
                } catch (RuntimeException ex) {
                    log.warn("Artifact orphan cleanup failed; key={}", object.key(), ex);
                }
            }
            if (page.size() < 100) {
                orphanScanCursor = "";
                break;
            }
        }
        return removed;
    }

    @Scheduled(fixedDelayString = "${noteweave.artifact.file-reconcile-delay-ms:60000}")
    public void reconcileDegradedFiles() {
        List<String> versionIds = jdbcTemplate.queryForList("""
                select id from artifact_version where delivery_status = 'DEGRADED'
                order by created_at asc limit 10
                """, String.class);
        for (String versionId : versionIds) {
            try {
                ExportRow version = loadVersionById(versionId);
                List<ArtifactFileMetadataResponse> files = listFiles(versionId);
                requireCompletePublishedFileSet(version, files);
                for (ArtifactFileMetadataResponse file : files) {
                    byte[] existing;
                    try {
                        existing = objectStorage.read(file.bucketName(), file.objectKey());
                    } catch (RuntimeException ex) {
                        existing = new byte[0];
                    }
                    if (sha256(existing).equals(file.checksumSha256())) continue;
                    byte[] recovered = MARKDOWN.equals(file.fileFormat())
                            ? version.contentMarkdown().getBytes(StandardCharsets.UTF_8)
                            : PDF.equals(file.fileFormat())
                                    ? fetchWorkerExport(version.originTaskId(), file.fileName())
                                    : new byte[0];
                    if (!sha256(recovered).equals(file.checksumSha256())) {
                        throw new BusinessException("ARTIFACT_FILE_RECONCILE_MISMATCH",
                                "恢复文件与已提交摘要不匹配", HttpStatus.CONFLICT);
                    }
                    objectStorage.write(file.bucketName(), file.objectKey(), recovered);
                }
                jdbcTemplate.update("update artifact_version set delivery_status = 'READY' where id = ?",
                        versionId);
            } catch (RuntimeException ex) {
                log.warn("Artifact file reconciliation deferred; versionId={}", versionId, ex);
            }
        }
    }

    private void requireCompletePublishedFileSet(ExportRow version,
                                                 List<ArtifactFileMetadataResponse> files) {
        if (files.stream().noneMatch(file -> MARKDOWN.equals(file.fileFormat())
                && "READY".equals(file.status()))) {
            throw new BusinessException("ARTIFACT_REQUIRED_FILE_MISSING",
                    "已发布版本缺少 Markdown 文件", HttpStatus.CONFLICT);
        }
        String pdfName = readCompiledFileNameIfPresent(version.resultPayloadJson());
        if (!pdfName.isBlank() && files.stream().noneMatch(file -> PDF.equals(file.fileFormat())
                && pdfName.equals(file.fileName()) && "READY".equals(file.status()))) {
            throw new BusinessException("ARTIFACT_REQUIRED_FILE_MISSING",
                    "已发布版本缺少 PDF 文件", HttpStatus.CONFLICT);
        }
        try {
            JsonNode required = objectMapper.readTree(version.resultPayloadJson())
                    .path("candidate").path("required_files");
            if (!required.isArray()) return; // Historical versions predate manifests.
            for (JsonNode expected : required) {
                boolean present = files.stream().anyMatch(file ->
                        expected.path("role").asText().equals(file.fileRole())
                        && expected.path("variant").asText().equals(file.variant())
                        && expected.path("sequence_no").asInt(-1) == file.sequenceNo()
                        && expected.path("checksum_sha256").asText().equals(file.checksumSha256())
                        && "READY".equals(file.status()));
                if (!present) {
                    throw new BusinessException("ARTIFACT_REQUIRED_FILE_MISSING",
                            "已发布版本缺少 Candidate 声明的文件", HttpStatus.CONFLICT);
                }
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("ARTIFACT_FILE_MANIFEST_INVALID",
                    "已发布版本的文件清单无效", HttpStatus.CONFLICT);
        }
    }

    private void requireReadyDelivery(String versionId) {
        String status = jdbcTemplate.queryForObject(
                "select delivery_status from artifact_version where id = ?", String.class, versionId);
        if (!"READY".equals(status)) {
            throw new BusinessException("ARTIFACT_FILE_DEGRADED", "产物文件暂不可用", HttpStatus.CONFLICT);
        }
    }

    private void requireVersionSourcesVisible(ExportRow version) {
        if (version.originTaskId() == null || version.originTaskId().isBlank()) return;
        List<String> scopes = jdbcTemplate.queryForList("""
                select s.source_scope_snapshot_json
                from artifact_job_run r
                join artifact_run_input_snapshot s on s.id = r.input_snapshot_id
                where r.task_id = ?
                """, String.class, version.originTaskId());
        if (scopes.isEmpty()) return; // Historical versions predate frozen input snapshots.
        try {
            JsonNode sources = objectMapper.readTree(scopes.get(0));
            if (!sources.isArray()) throw new IllegalArgumentException("source scope must be an array");
            for (JsonNode source : sources) {
                String sourceId = source.isTextual() ? source.asText() : source.path("source_id").asText();
                String snapshotId = source.path("source_snapshot_id").asText();
                Integer visible = jdbcTemplate.queryForObject("""
                        select count(*) from source s
                        join source_snapshot ss on ss.source_id = s.id
                        where s.workspace_id = (select workspace_id from artifact_job where id = ?)
                          and s.id = ? and s.status = 'READY'
                          and (? = '' or ss.id = ?)
                        """, Integer.class, version.artifactJobId(), sourceId, snapshotId, snapshotId);
                if (visible == null || visible == 0) {
                    throw new BusinessException("ARTIFACT_SOURCE_REVOKED",
                            "产物来源已删除、撤权或不可用", HttpStatus.CONFLICT);
                }
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("ARTIFACT_SOURCE_SCOPE_INVALID",
                    "产物来源快照无法校验", HttpStatus.CONFLICT);
        }
    }

    public List<ArtifactFileMetadataResponse> listFiles(String artifactVersionId) {
        return jdbcTemplate.query("""
                select id, file_format, file_name, media_type, storage_backend, bucket_name,
                       object_key, size_bytes, checksum_sha256, status, created_at
                       , coalesce(error_message, '') as error_message, file_role, variant, sequence_no
                from artifact_file where artifact_version_id = ?
                order by file_role asc, variant asc, sequence_no asc
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
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("file_role"), rs.getString("variant"), rs.getInt("sequence_no")
        ), artifactVersionId);
    }

    public List<ArtifactFileMetadataResponse> listVersionFiles(
            String workspaceId, String artifactJobId, int versionNo) {
        return listFiles(loadVersion(workspaceId, artifactJobId, versionNo).versionId());
    }

    public ArtifactDownloadFile downloadFile(String workspaceId, String artifactJobId,
                                             int versionNo, String fileId) {
        ExportRow version = loadVersion(workspaceId, artifactJobId, versionNo);
        requireVersionSourcesVisible(version);
        requireReadyDelivery(version.versionId());
        List<ArtifactFileMetadataResponse> matches = listFiles(version.versionId()).stream()
                .filter(file -> fileId.equals(file.fileId()))
                .toList();
        if (matches.size() != 1 || !"READY".equals(matches.get(0).status())) {
            throw new BusinessException("ARTIFACT_FILE_NOT_READY", "产物文件不可下载", HttpStatus.CONFLICT);
        }
        ArtifactFileMetadataResponse file = matches.get(0);
        try {
            byte[] bytes = objectStorage.read(file.bucketName(), file.objectKey());
            if (bytes.length == 0 || !sha256(bytes).equals(file.checksumSha256())) {
                throw new IllegalStateException("artifact file checksum mismatch");
            }
            return new ArtifactDownloadFile(file.fileName(), file.mediaType(), bytes);
        } catch (RuntimeException ex) {
            jdbcTemplate.update("update artifact_version set delivery_status = 'DEGRADED' where id = ?",
                    version.versionId());
            throw new BusinessException("ARTIFACT_FILE_DEGRADED", "产物文件暂不可用", HttpStatus.CONFLICT);
        }
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
        upsertFileMetadata(row, format, primaryRole(format), "", 0, fileName, mediaType,
                objectKey, sizeBytes, checksum, status, errorMessage);
    }

    private void upsertFileMetadata(
            ExportRow row, String format, String role, String variant, int sequenceNo,
            String fileName, String mediaType, String objectKey, long sizeBytes,
            String checksum, String status, String errorMessage
    ) {
        int updated = jdbcTemplate.update("""
                update artifact_file
                set file_format = ?, file_name = ?, media_type = ?, storage_backend = ?,
                    bucket_name = ?, object_key = ?,
                    size_bytes = ?, checksum_sha256 = ?, status = ?, error_message = ?
                where artifact_version_id = ? and file_role = ? and variant = ? and sequence_no = ?
                """,
                format, fileName, mediaType, objectStorage.backendName(), exportBucket, objectKey,
                sizeBytes, checksum, status, errorMessage, row.versionId(), role, variant, sequenceNo);
        if (updated == 0) {
            jdbcTemplate.update("""
                    insert into artifact_file(
                        id, artifact_version_id, file_format, file_role, variant, sequence_no,
                        file_name, media_type, storage_backend,
                        bucket_name, object_key, size_bytes, checksum_sha256, status, error_message
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    Ids.newId(), row.versionId(), format, role, variant, sequenceNo,
                    fileName, mediaType, objectStorage.backendName(),
                    exportBucket, objectKey, sizeBytes, checksum, status, errorMessage);
        }
    }

    private static String primaryRole(String format) {
        return MARKDOWN.equals(format) ? "PRIMARY_MARKDOWN" :
                PDF.equals(format) ? "PRIMARY_PDF" : "LEGACY_" + format;
    }

    private byte[] fetchWorkerExport(String taskId, String fileName) {
        if (taskId == null || taskId.isBlank()) {
            throw new BusinessException("ARTIFACT_EXPORT_TASK_MISSING", "产物版本缺少来源任务，无法归档 PDF");
        }
        return artifactWorkerClient.fetch(taskId, fileName);
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
                select file_name, bucket_name, object_key, checksum_sha256
                from artifact_file
                where artifact_version_id = ? and file_format = ? and status = 'READY'
                """, (rs, rowNum) -> new StoredFile(
                rs.getString("file_name"), rs.getString("bucket_name"), rs.getString("object_key"),
                rs.getString("checksum_sha256")
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
            if ("PPTX".equals(exportTrace.path("format").asText())) {
                return ""; // PPTX is fetched through the Candidate's PRIMARY_PPTX role.
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

    private record StoredFile(String fileName, String bucketName, String objectKey, String checksum) {
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
