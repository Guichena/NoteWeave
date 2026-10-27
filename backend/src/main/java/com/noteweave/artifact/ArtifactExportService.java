package com.noteweave.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.noteweave.common.SensitiveErrorMessageSanitizer;
import com.noteweave.source.eligibility.AccessPurpose;
import com.noteweave.source.eligibility.EligibilityDecision;
import com.noteweave.source.eligibility.EligibilityStatus;
import com.noteweave.source.eligibility.MaterialEligibilityPort;
import com.noteweave.source.eligibility.OriginRef;
import com.noteweave.storage.ObjectStorage;
import com.noteweave.research.ResearchGeneratedSourceReadGate;
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
    private final ArtifactMemoryRevisionGuard memoryRevisionGuard;
    private final ArtifactContextV2ShadowSnapshotService contextV2Snapshots;
    private final ResearchGeneratedSourceReadGate generatedSourceGate;
    private final MaterialEligibilityPort eligibilityPort;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;
    private final String exportBucket;
    private String orphanScanCursor = "";

    /**
     * ART-02：文件补偿的最大尝试次数与退避步长。
     * 用字段注入而不是构造参数——本类有多个测试构造器，加参数会牵连所有调用点；
     * 测试需要更小的上限时用 {@code ReflectionTestUtils.setField} 覆盖。
     */
    @org.springframework.beans.factory.annotation.Value(
            "${noteweave.artifact.file-reconcile-max-attempts:5}")
    private int fileReconcileMaxAttempts = 5;

    @org.springframework.beans.factory.annotation.Value(
            "${noteweave.artifact.file-reconcile-backoff-seconds:60}")
    private long fileReconcileBackoffSeconds = 60;

    public ArtifactExportService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ObjectStorage objectStorage,
            ArtifactWorkerExportClient artifactWorkerClient,
            ArtifactSkillCatalogService skillCatalog,
            ArtifactMemoryRevisionGuard memoryRevisionGuard,
            ArtifactContextV2ShadowSnapshotService contextV2Snapshots,
            ResearchGeneratedSourceReadGate generatedSourceGate,
            com.noteweave.config.NoteWeaveProperties properties
    ) {
        this(jdbcTemplate, objectMapper, objectStorage, artifactWorkerClient, skillCatalog,
                memoryRevisionGuard, contextV2Snapshots, generatedSourceGate, null, null, properties);
    }

    public ArtifactExportService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ObjectStorage objectStorage,
            ArtifactWorkerExportClient artifactWorkerClient,
            ArtifactSkillCatalogService skillCatalog,
            ArtifactMemoryRevisionGuard memoryRevisionGuard,
            ArtifactContextV2ShadowSnapshotService contextV2Snapshots,
            ResearchGeneratedSourceReadGate generatedSourceGate,
            MaterialEligibilityPort eligibilityPort,
            com.noteweave.config.NoteWeaveProperties properties
    ) {
        this(jdbcTemplate, objectMapper, objectStorage, artifactWorkerClient, skillCatalog,
                memoryRevisionGuard, contextV2Snapshots, generatedSourceGate, eligibilityPort,
                null, properties);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ArtifactExportService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ObjectStorage objectStorage,
            ArtifactWorkerExportClient artifactWorkerClient,
            ArtifactSkillCatalogService skillCatalog,
            ArtifactMemoryRevisionGuard memoryRevisionGuard,
            ArtifactContextV2ShadowSnapshotService contextV2Snapshots,
            ResearchGeneratedSourceReadGate generatedSourceGate,
            MaterialEligibilityPort eligibilityPort,
            org.springframework.transaction.PlatformTransactionManager transactionManager,
            com.noteweave.config.NoteWeaveProperties properties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.objectStorage = objectStorage;
        this.artifactWorkerClient = artifactWorkerClient;
        this.skillCatalog = skillCatalog;
        this.memoryRevisionGuard = memoryRevisionGuard;
        this.contextV2Snapshots = contextV2Snapshots;
        this.generatedSourceGate = generatedSourceGate;
        this.eligibilityPort = eligibilityPort;
        this.transactionManager = transactionManager;
        this.exportBucket = properties.storage().minio().bucketExport();
    }

    /**
     * 在独立短事务中执行提交段；无事务管理器时（测试构造）直接执行。
     */
    public <T> T runInShortTransaction(java.util.function.Supplier<T> action) {
        if (transactionManager == null) {
            return action.get();
        }
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> action.get());
    }

    /**
     * 归档导出：文件写入与元数据落库按文件独立提交，不在长数据库事务内做存储 IO。
     */
    public List<ArtifactFileMetadataResponse> materializeExports(String artifactVersionId) {
        ExportRow row = loadVersionById(artifactVersionId);
        // 归档导出同样要过来源校验，避免绕过下载接口直接写出引用已删除来源的文件
        requireVersionSourcesVisible(row);
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

    /**
     * 完成回执的 prepare 段（ART-01）：校验角色/格式/摘要、写 staged 对象、回读校验摘要，
     * 全部在数据库事务外完成。提交段只需 {@link #commitStagedFiles(String, List)} 写元数据。
     *
     * <p>staged 对象键依赖本次回调预留的版本号，因此版本号从 {@code artifact_job_run} 读取，
     * 与提交事务里 {@code completeFromWorker} 使用的是同一个值。</p>
     */
    public List<StagedFile> prepareCompletionStagedFiles(String taskId, List<PreparedFile> files) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select aj.skill_key, r.reserved_version_id
                from artifact_job_run r
                join artifact_job aj on aj.id = r.artifact_job_id
                where r.task_id = ?
                """, taskId);
        String versionId = row.get("reserved_version_id") == null
                ? null : String.valueOf(row.get("reserved_version_id"));
        if (versionId == null || versionId.isBlank()) {
            throw new BusinessException("ARTIFACT_VERSION_NOT_RESERVED",
                    "产物版本尚未预留，不能发布文件", HttpStatus.CONFLICT);
        }
        return doPrepareStagedFiles(String.valueOf(row.get("skill_key")), taskId, versionId, files);
    }

    /** 已有版本的 prepare 段：给已发布的版本补附加文件（如预览）时使用。 */
    public List<StagedFile> prepareStagedFilesForVersion(String versionId, List<PreparedFile> files) {
        ExportRow row = loadVersionById(versionId);
        String skillKey = jdbcTemplate.queryForObject(
                "select skill_key from artifact_version where id = ?", String.class, versionId);
        return doPrepareStagedFiles(skillKey, row.originTaskId(), versionId, files);
    }

    /**
     * prepare 段：校验角色/格式/摘要并写入不可变 staged 对象，全部远程 IO 都在数据库事务外。
     * 发布事务失败时 staged 对象由 cleanupOrphanedStagedFiles 按无引用规则回收。
     */
    public List<StagedFile> prepareStagedFiles(
            String skillKey, String originTaskId, String versionId, List<PreparedFile> files) {
        return doPrepareStagedFiles(skillKey, originTaskId, versionId, files);
    }

    /** 目标版本当前已就绪（READY）的文件角色集合；版本尚不存在时为空。 */
    private java.util.Set<String> existingReadyRoles(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return java.util.Set.of();
        }
        List<String> roles = jdbcTemplate.queryForList("""
                select distinct file_role from artifact_file
                where artifact_version_id = ? and status = 'READY'
                """, String.class, versionId);
        return new java.util.HashSet<>(roles);
    }

    private List<StagedFile> doPrepareStagedFiles(
            String skillKey, String originTaskId, String versionId, List<PreparedFile> files) {
        java.util.Set<String> presentRoles = new java.util.HashSet<>();
        for (PreparedFile file : files) presentRoles.add(file.role());
        if (!presentRoles.containsAll(skillCatalog.requiredFileRoles(skillKey))) {
            // 版本级覆盖：对既有版本补充附加文件（如预览）时，已就绪角色与本次批次合并判断；
            // 新版本发布仍必须由本批次自己覆盖全部必需角色
            java.util.Set<String> combined = new java.util.HashSet<>(presentRoles);
            combined.addAll(existingReadyRoles(versionId));
            if (!combined.containsAll(skillCatalog.requiredFileRoles(skillKey))) {
                throw new BusinessException("ARTIFACT_REQUIRED_FILE_MISSING",
                        "已发布 Skill 的必需文件角色不齐", HttpStatus.CONFLICT);
            }
        }
        java.util.Set<String> fileKeys = new java.util.HashSet<>();
        List<StagedFile> stagedFiles = new java.util.ArrayList<>();
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
                    originTaskId, versionId, file.role(),
                    file.variant().isEmpty() ? "default" : file.variant(),
                    file.sequenceNo(), safeFileName(file.fileName()));
            objectStorage.write(exportBucket, objectKey, file.content());
            byte[] stored = objectStorage.read(exportBucket, objectKey);
            if (!file.checksum().equals(sha256(stored))) {
                throw new BusinessException("ARTIFACT_FILE_STORE_MISMATCH",
                        "暂存文件写入后摘要不匹配", HttpStatus.CONFLICT);
            }
            stagedFiles.add(new StagedFile(objectKey, file));
        }
        return List.copyOf(stagedFiles);
    }

    /**
     * commit 段：短事务内只写文件元数据并发布指针，不执行任何远程存储调用。
     * 与版本插入同事务调用时，DB 提交后文件与版本引用即一致。
     */
    public void commitStagedFiles(String versionId, List<StagedFile> stagedFiles) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Artifact staged files must commit in a short transaction");
        }
        ExportRow row = loadVersionById(versionId);
        commitStagedFiles(row, stagedFiles);
    }

    private void commitStagedFiles(ExportRow row, List<StagedFile> stagedFiles) {
        for (StagedFile staged : stagedFiles) {
            PreparedFile file = staged.file();
            upsertFileMetadata(row, file.format(), file.role(), file.variant(), file.sequenceNo(),
                    file.fileName(), file.mediaType(), staged.objectKey(),
                    file.content().length, file.checksum(), "READY", "");
        }
    }

    /** staged 对象引用；objectKey 不可变，发布失败时按孤儿规则回收。 */
    public record StagedFile(String objectKey, PreparedFile file) {
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

    /**
     * 文件补偿批次（ART-02）。
     *
     * <p>只取「到期且未进终态」的 DEGRADED 版本：修不好的版本会被推后并计数，
     * 不再每轮都排在队头把批次占满；达到上限转终态，留给人工重驱。</p>
     */
    @Scheduled(fixedDelayString = "${noteweave.artifact.file-reconcile-delay-ms:60000}")
    public void reconcileDegradedFiles() {
        List<String> versionIds = jdbcTemplate.queryForList("""
                select id from artifact_version
                where delivery_status = 'DEGRADED'
                  and reconcile_exhausted_at is null
                  and (reconcile_next_attempt_at is null or reconcile_next_attempt_at <= current_timestamp)
                order by coalesce(reconcile_next_attempt_at, created_at) asc, created_at asc
                limit 10
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
                    byte[] recovered = "PRIMARY_MARKDOWN".equals(file.fileRole())
                            ? version.contentMarkdown().getBytes(StandardCharsets.UTF_8)
                            : fetchWorkerExport(version.originTaskId(), file.fileName());
                    if (!sha256(recovered).equals(file.checksumSha256())) {
                        throw new BusinessException("ARTIFACT_FILE_RECONCILE_MISMATCH",
                                "恢复文件与已提交摘要不匹配", HttpStatus.CONFLICT);
                    }
                    objectStorage.write(file.bucketName(), file.objectKey(), recovered);
                    byte[] persisted = objectStorage.read(file.bucketName(), file.objectKey());
                    if (!sha256(persisted).equals(file.checksumSha256())) {
                        throw new BusinessException("ARTIFACT_FILE_RECONCILE_MISMATCH",
                                "恢复文件写入后摘要不匹配", HttpStatus.CONFLICT);
                    }
                }
                markFileReconcileReady(versionId);
            } catch (RuntimeException ex) {
                postponeFileReconcile(versionId, ex);
            }
        }
    }

    private void markFileReconcileReady(String versionId) {
        jdbcTemplate.update("""
                update artifact_version
                set delivery_status = 'READY', reconcile_attempts = 0,
                    reconcile_next_attempt_at = null, reconcile_exhausted_at = null
                where id = ?
                """, versionId);
    }

    /**
     * 补偿失败：计数并把下一次尝试推到队尾；到上限转终态并告警，
     * 让这一行不再参与自动补偿（人工重驱可重新放回队列）。
     */
    private void postponeFileReconcile(String versionId, RuntimeException failure) {
        Integer current = jdbcTemplate.queryForObject(
                "select reconcile_attempts from artifact_version where id = ?",
                Integer.class, versionId);
        int attempts = (current == null ? 0 : current) + 1;
        if (attempts >= fileReconcileMaxAttempts) {
            jdbcTemplate.update("""
                    update artifact_version
                    set reconcile_attempts = ?, reconcile_next_attempt_at = null,
                        reconcile_exhausted_at = current_timestamp
                    where id = ?
                    """, attempts, versionId);
            log.error("Artifact file reconciliation exhausted; versionId={} attempts={}",
                    versionId, attempts, failure);
            return;
        }
        jdbcTemplate.update("""
                update artifact_version
                set reconcile_attempts = ?,
                    reconcile_next_attempt_at = timestampadd(second, ?, current_timestamp)
                where id = ?
                """, attempts, fileReconcileBackoffSeconds * attempts, versionId);
        log.warn("Artifact file reconciliation deferred; versionId={} attempts={}",
                versionId, attempts, failure);
    }

    /**
     * 人工重驱（ART-02）：清空计数、退避与终态，让该版本重新进入补偿队列。
     *
     * @return true 表示确实重置了一行 DEGRADED 版本
     */
    public boolean retryFileReconcile(String versionId) {
        int updated = jdbcTemplate.update("""
                update artifact_version
                set reconcile_attempts = 0, reconcile_next_attempt_at = null,
                    reconcile_exhausted_at = null
                where id = ? and delivery_status = 'DEGRADED'
                """, versionId);
        return updated == 1;
    }

    /** 补偿队列指标：等待中的、已退避的、已进终态的，供告警与人工排查。 */
    public java.util.Map<String, Long> fileReconcileMetrics() {
        java.util.Map<String, Long> metrics = new java.util.LinkedHashMap<>();
        metrics.put("degraded_pending", jdbcTemplate.queryForObject("""
                select count(*) from artifact_version
                where delivery_status = 'DEGRADED' and reconcile_exhausted_at is null
                  and (reconcile_next_attempt_at is null or reconcile_next_attempt_at <= current_timestamp)
                """, Long.class));
        metrics.put("degraded_backing_off", jdbcTemplate.queryForObject("""
                select count(*) from artifact_version
                where delivery_status = 'DEGRADED' and reconcile_exhausted_at is null
                  and reconcile_next_attempt_at > current_timestamp
                """, Long.class));
        metrics.put("degraded_exhausted", jdbcTemplate.queryForObject("""
                select count(*) from artifact_version
                where delivery_status = 'DEGRADED' and reconcile_exhausted_at is not null
                """, Long.class));
        return metrics;
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

    public void requireVersionReadable(String workspaceId, String artifactJobId, int versionNo) {
        requireVersionSourcesVisible(loadVersion(workspaceId, artifactJobId, versionNo),
                AccessPurpose.CURRENT_EVIDENCE);
    }

    /** 按用途校验版本可读：详情/下载走当前证据，另存/写回等再发布动作走 REPUBLISH。 */
    public void requireVersionReadable(
            String workspaceId, String artifactJobId, int versionNo, AccessPurpose purpose) {
        requireVersionSourcesVisible(loadVersion(workspaceId, artifactJobId, versionNo), purpose);
    }

    public boolean versionVisibleForListing(String workspaceId, String artifactJobId, int versionNo) {
        try {
            requireVersionReadable(workspaceId, artifactJobId, versionNo);
            return true;
        } catch (BusinessException failure) {
            return switch (failure.code()) {
                case "ARTIFACT_SOURCE_REVOKED", "ARTIFACT_MEMORY_REVOKED",
                     "RESEARCH_SOURCE_CONTEXT_REDACTED",
                     // ART-03：来源上下文已不可验证的版本在列表里隐藏，而不是让整页报错
                     "ARTIFACT_SOURCE_CONTEXT_MISSING" -> false;
                default -> throw failure;
            };
        }
    }

    private void requireVersionSourcesVisible(ExportRow version) {
        requireVersionSourcesVisible(version, AccessPurpose.CURRENT_EVIDENCE);
    }

    /**
     * 版本依赖门控：Memory revision、冻结输入与上游引用统一经 C1 端口评估。
     * 生成来源的依赖闭包由端口递归解析，这里不复制 Research SQL；
     * 暂不可用与撤销使用不同的原因文案，方便前端区分可恢复与终态。
     */
    private void requireVersionSourcesVisible(ExportRow version, AccessPurpose purpose) {
        if (version.originTaskId() == null || version.originTaskId().isBlank()) return;
        memoryRevisionGuard.requireActive(version.originTaskId());
        List<VersionInput> inputs = jdbcTemplate.query("""
                select s.id, s.workspace_id, s.user_requirement,
                       s.source_scope_snapshot_json, s.upstream_refs_json
                from artifact_job_run r
                join artifact_run_input_snapshot s on s.id = r.input_snapshot_id
                where r.task_id = ?
                """, (rs, index) -> new VersionInput(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getString(5)), version.originTaskId());
        if (inputs.isEmpty()) {
            // ART-03：跑过 artifact_job_run 且当时冻结了输入快照，现在却查不到那份快照，
            // 说明来源上下文已被删除或清理，依赖是否仍然有效无从验证——不能当成「没有依赖」放行。
            // 早于快照机制的存量版本没有 input_snapshot_id，仍按历史行为放行。
            if (frozenSnapshotLost(version.originTaskId())) {
                throw new BusinessException("ARTIFACT_SOURCE_CONTEXT_MISSING",
                        "产物来源上下文已不可用，无法确认依赖是否仍然有效", HttpStatus.CONFLICT);
            }
            return;
        }
        VersionInput input = inputs.get(0);
        contextV2Snapshots.activeRequirement(version.originTaskId(), input.workspaceId(),
                input.snapshotId(), input.requirement());
        try {
            JsonNode sources = objectMapper.readTree(input.sourceScopeJson());
            if (!sources.isArray()) throw new IllegalArgumentException("source scope must be an array");
            java.util.List<OriginRef> scopeRefs = new java.util.ArrayList<>();
            java.util.Map<String, String> snapshotsBySource = new java.util.LinkedHashMap<>();
            for (JsonNode source : sources) {
                String sourceId = source.isTextual() ? source.asText() : source.path("source_id").asText();
                String snapshotId = source.path("source_snapshot_id").asText();
                scopeRefs.add(OriginRef.internalSource(sourceId));
                snapshotsBySource.put(sourceId, snapshotId == null ? "" : snapshotId);
            }
            requireOriginsEligible(input.workspaceId(), scopeRefs, purpose);
            for (Map.Entry<String, String> entry : snapshotsBySource.entrySet()) {
                if (!entry.getValue().isEmpty() && !snapshotRowExists(entry.getKey(), entry.getValue())) {
                    throw new BusinessException("ARTIFACT_SOURCE_REVOKED",
                            "产物来源已删除、撤权或不可用", HttpStatus.CONFLICT);
                }
            }
            JsonNode upstream = objectMapper.readTree(input.upstreamRefsJson());
            if (!upstream.isArray()) throw new IllegalArgumentException("upstream refs must be an array");
            for (JsonNode ref : upstream) {
                String refType = ref.path("ref_type").asText();
                if ("RESEARCH_REPORT".equals(refType)) {
                    List<String> reportSourceIds = jdbcTemplate.queryForList("""
                            select s.id from source s
                            join source_snapshot ss on ss.source_id = s.id
                            where s.workspace_id = ? and s.generated_by = 'research_agent'
                              and s.generated_ref_id = ? and ss.id = ?
                            """, String.class, input.workspaceId(), ref.path("ref_id").asText(),
                            ref.path("revision_id").asText());
                    if (reportSourceIds.size() != 1) {
                        throw new BusinessException("ARTIFACT_SOURCE_REVOKED",
                                "产物来源已删除、撤权或不可用", HttpStatus.CONFLICT);
                    }
                    requireOriginsEligible(input.workspaceId(),
                            List.of(OriginRef.internalSource(reportSourceIds.get(0))), purpose);
                    generatedSourceGate.requireReadable(input.workspaceId(), "research_agent",
                            ref.path("ref_id").asText());
                } else if ("SOURCE_SNAPSHOT".equals(refType)) {
                    requireOriginsEligible(input.workspaceId(),
                            List.of(OriginRef.internalSource(ref.path("ref_id").asText())), purpose);
                }
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("ARTIFACT_SOURCE_SCOPE_INVALID",
                    "产物来源快照无法校验", HttpStatus.CONFLICT);
        }
    }

    /** 供 worker 完成提交路径复用的来源资格校验（当前证据用途，含生成依赖闭包）。 */
    public void requireSourcesEvidenceEligible(String workspaceId, java.util.List<String> sourceIds) {
        requireOriginsEligible(workspaceId,
                sourceIds.stream()
                        .filter(id -> id != null && !id.isBlank())
                        .map(OriginRef::internalSource).toList(),
                AccessPurpose.CURRENT_EVIDENCE);
    }

    /** 经 C1 端口评估来源集合；无端口时回退到生成来源门控旧行为。 */
    private void requireOriginsEligible(
            String workspaceId, List<OriginRef> origins, AccessPurpose purpose) {
        if (origins.isEmpty()) return;
        if (eligibilityPort == null) {
            for (OriginRef origin : origins) {
                requireGeneratedSourceReadable(workspaceId, origin.sourceId());
            }
            return;
        }
        Map<String, EligibilityDecision> decisions = eligibilityPort.assess(workspaceId, origins, purpose);
        for (OriginRef origin : origins) {
            EligibilityDecision decision = decisions.get(origin.originKey());
            if (decision == null) {
                throw new BusinessException("ARTIFACT_SOURCE_SCOPE_INVALID",
                        "产物来源快照无法校验", HttpStatus.CONFLICT);
            }
            if (!decision.allowed(purpose)) {
                if ("RESEARCH_CONTEXT_REDACTED".equals(decision.reasonCode())) {
                    // 研究上下文脱敏是明确的撤销态：透出专属错误码，前端与列表过滤按脱敏处理
                    throw new BusinessException("RESEARCH_SOURCE_CONTEXT_REDACTED",
                            "Generated Research Source is no longer readable", HttpStatus.CONFLICT);
                }
                boolean recoverable = decision.status() == EligibilityStatus.TEMPORARILY_UNAVAILABLE
                        || decision.status() == EligibilityStatus.STALE_SNAPSHOT;
                throw new BusinessException("ARTIFACT_SOURCE_REVOKED",
                        recoverable ? "产物来源暂不可用，稍后可重试" : "产物来源已删除、撤权或不可用",
                        HttpStatus.CONFLICT);
            }
        }
    }

    private boolean snapshotRowExists(String sourceId, String snapshotId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from source_snapshot
                where source_id = ? and id = ?
                """, Integer.class, sourceId, snapshotId);
        return count != null && count > 0;
    }

    /**
     * ART-03：该来源任务是否「冻结过快照、但现在快照已经不在」。
     *
     * <p>用它区分两种「查不到冻结输入」的情况：早于快照机制的存量版本（没有
     * {@code input_snapshot_id}，允许放行），与快照被删除/清理的新版本（必须拒绝）。</p>
     */
    private boolean frozenSnapshotLost(String taskId) {
        Integer lost = jdbcTemplate.queryForObject("""
                select count(*)
                from artifact_job_run r
                where r.task_id = ? and r.input_snapshot_id is not null
                  and not exists (
                      select 1 from artifact_run_input_snapshot s where s.id = r.input_snapshot_id
                  )
                """, Integer.class, taskId);
        return lost != null && lost > 0;
    }

    private void requireGeneratedSourceReadable(String workspaceId, String sourceId) {
        if (sourceId.isBlank()) throw new BusinessException("ARTIFACT_SOURCE_SCOPE_INVALID",
                "产物来源快照无法校验", HttpStatus.CONFLICT);
        List<SourceOrigin> origins = jdbcTemplate.query("""
                select coalesce(generated_by, ''), coalesce(generated_ref_id, '')
                from source where workspace_id = ? and id = ? and status = 'READY'
                """, (rs, rowNum) -> new SourceOrigin(rs.getString(1), rs.getString(2)),
                workspaceId, sourceId);
        if (origins.size() != 1) {
            throw new BusinessException("ARTIFACT_SOURCE_REVOKED",
                    "产物来源已删除、撤权或不可用", HttpStatus.CONFLICT);
        }
        SourceOrigin origin = origins.get(0);
        generatedSourceGate.requireReadable(workspaceId, origin.generatedBy(), origin.generatedRefId());
    }

    private record SourceOrigin(String generatedBy, String generatedRefId) {}

    private record VersionInput(String snapshotId, String workspaceId,
                                String requirement, String sourceScopeJson,
                                String upstreamRefsJson) {}

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
        ExportRow version = loadVersion(workspaceId, artifactJobId, versionNo);
        requireVersionSourcesVisible(version);
        return listFiles(version.versionId());
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
        // NR07：已有 READY 归档行不再回源 Worker——Worker 离线或其临时文件被清理时，
        // 已发布版本的归档记录与下载不受影响，回滚从归档准备；仅未归档时才回源补档。
        if (hasReadyFile(row.versionId(), PDF)) {
            return;
        }
        try {
            persistFile(row, PDF, fileName, "application/pdf", fetchWorkerExport(row.originTaskId(), fileName));
        } catch (RuntimeException ex) {
            // NR07：补取失败不得覆盖已发布的有效记录（并发窗口内他方已置 READY 时保持不动）
            if (hasReadyFile(row.versionId(), PDF)) {
                log.warn("Artifact PDF fetch failed but READY archive appeared; keep archive: versionId={}",
                        row.versionId());
                return;
            }
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

    /** NR07：该版本该格式是否已有 READY 归档行（不回源、失败不覆盖的判定依据）。 */
    private boolean hasReadyFile(String versionId, String format) {
        Integer ready = jdbcTemplate.queryForObject("""
                select count(*) from artifact_file
                where artifact_version_id = ? and file_format = ? and status = 'READY'
                """, Integer.class, versionId, format);
        return ready != null && ready > 0;
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
