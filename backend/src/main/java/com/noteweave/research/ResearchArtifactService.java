package com.noteweave.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.noteweave.knowledge.WikiIngestService;
import com.noteweave.source.SourceCatalogVersionService;
import com.noteweave.source.SourceParseService;
import com.noteweave.source.eligibility.AccessPurpose;
import com.noteweave.source.eligibility.EligibilityDecision;
import com.noteweave.source.eligibility.MaterialEligibilityPort;
import com.noteweave.source.eligibility.OriginRef;
import com.noteweave.storage.ObjectStorage;
import com.noteweave.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ResearchArtifactService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final WorkspaceService workspaceService;
    private final ObjectStorage storage;
    private final SourceParseService sourceParseService;
    private final WikiIngestService wikiIngestService;
    private final SourceCatalogVersionService sourceCatalogVersionService;
    private final ResearchContextV2Gate contextGate;
    private final ResearchGeneratedSourceReadGate reportDependencyGate;
    private final MaterialEligibilityPort eligibilityPort;

    /** 文件对象生命周期网关；未注入时保留既有内联引用 SQL（legacy 路径）。 */
    private com.noteweave.source.storage.FileObjectLifecycleGateway fileObjectLifecycle;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setFileObjectLifecycle(com.noteweave.source.storage.FileObjectLifecycleGateway fileObjectLifecycle) {
        this.fileObjectLifecycle = fileObjectLifecycle;
    }

    /** 生命周期设施（NR13）：prepare 阶段的零引用状态决策走统一协议。 */
    private com.noteweave.storage.FileObjectLifecycleService fileObjectLifecycleService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setFileObjectLifecycleService(com.noteweave.storage.FileObjectLifecycleService fileObjectLifecycleService) {
        this.fileObjectLifecycleService = fileObjectLifecycleService;
    }
    private final TransactionTemplate transactionTemplate;

    public ResearchArtifactService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            WorkspaceService workspaceService,
            ObjectStorage storage,
            SourceParseService sourceParseService,
            WikiIngestService wikiIngestService,
            SourceCatalogVersionService sourceCatalogVersionService
    ) {
        this(jdbcTemplate, objectMapper, workspaceService, storage, sourceParseService,
                wikiIngestService, sourceCatalogVersionService, null, null);
    }

    public ResearchArtifactService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            WorkspaceService workspaceService,
            ObjectStorage storage,
            SourceParseService sourceParseService,
            WikiIngestService wikiIngestService,
            SourceCatalogVersionService sourceCatalogVersionService,
            ResearchContextV2Gate contextGate
    ) {
        this(jdbcTemplate, objectMapper, workspaceService, storage, sourceParseService,
                wikiIngestService, sourceCatalogVersionService, contextGate, null);
    }

    public ResearchArtifactService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            WorkspaceService workspaceService,
            ObjectStorage storage,
            SourceParseService sourceParseService,
            WikiIngestService wikiIngestService,
            SourceCatalogVersionService sourceCatalogVersionService,
            ResearchContextV2Gate contextGate,
            ResearchGeneratedSourceReadGate reportDependencyGate
    ) {
        this(jdbcTemplate, objectMapper, workspaceService, storage, sourceParseService,
                wikiIngestService, sourceCatalogVersionService, contextGate, reportDependencyGate,
                null, null);
    }

    @Autowired
    public ResearchArtifactService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            WorkspaceService workspaceService,
            ObjectStorage storage,
            SourceParseService sourceParseService,
            WikiIngestService wikiIngestService,
            SourceCatalogVersionService sourceCatalogVersionService,
            ResearchContextV2Gate contextGate,
            ResearchGeneratedSourceReadGate reportDependencyGate,
            MaterialEligibilityPort eligibilityPort,
            org.springframework.transaction.PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.workspaceService = workspaceService;
        this.storage = storage;
        this.sourceParseService = sourceParseService;
        this.wikiIngestService = wikiIngestService;
        this.sourceCatalogVersionService = sourceCatalogVersionService;
        this.contextGate = contextGate;
        this.reportDependencyGate = reportDependencyGate;
        this.eligibilityPort = eligibilityPort;
        this.transactionTemplate = transactionManager == null ? null
                : new TransactionTemplate(transactionManager);
    }

    /**
     * 保存报告为来源：prepare（校验与文件准备，无长数据库事务）与 commit（短事务提交）两段。
     *
     * <p>重复保存先校验既有来源的当前使用资格，不能在依赖检查前直接返回既有来源；
     * 首次保存在提交事务内再次核对依赖，避免文件准备期间依赖被删除。</p>
     */
    public SaveResearchReportSourceResponse saveReportAsSource(String workspaceId, String researchRunId) {
        requireWorkspace(workspaceId);
        requireFrozenContext(researchRunId);
        SaveReportRow row = loadSaveReportRow(workspaceId, researchRunId);
        if (row.reportSourceId() != null && !row.reportSourceId().isBlank()
                && existingReportSourceUsable(workspaceId, row.reportSourceId())) {
            return loadSavedReportSource(workspaceId, row.reportSourceId());
        }
        // NR20：既有报告来源已删除/不可复用——继续走保存流程，由提交段把登记替换为新来源，
        // 不再把指向已删除资料的假成功返回给调用方，也不让已删除来源永久阻塞再次保存
        if (!"COMPLETED".equals(row.status())
                || row.finalReportMarkdown() == null
                || row.finalReportMarkdown().isBlank()) {
            throw new BusinessException(
                    "RESEARCH_REPORT_NOT_READY",
                    "Research report is not complete and cannot be saved as a source"
            );
        }
        requireReportDependenciesReadable(workspaceId, researchRunId);
        PreparedReportSource prepared = prepareReportSource(workspaceId, researchRunId, row);
        SaveResearchReportSourceResponse committed = commitPreparedReportSource(
                workspaceId, researchRunId, prepared);
        if (!committed.sourceId().equals(prepared.sourceId())) {
            // 并发保存已有赢家：不再重复解析与入队
            return committed;
        }
        sourceParseService.parseAndIndex(workspaceId, prepared.sourceId(), prepared.snapshotId(),
                prepared.reportBytes());
        wikiIngestService.enqueueAndRunSourceIngestIfEnabled(workspaceId, prepared.sourceId());
        return committed;
    }

    /** 重复保存时校验既有来源当前可用：已删除或依赖失效的来源不能继续作为有效发布结果返回。 */
    private void requireExistingReportSourceUsable(String workspaceId, String sourceId) {
        if (reportDependencyGate != null) {
            reportDependencyGate.requireSourceRepublishable(workspaceId, sourceId);
            return;
        }
        if (eligibilityPort == null) {
            return;
        }
        EligibilityDecision decision = eligibilityPort.assessOne(
                workspaceId, OriginRef.internalSource(sourceId), AccessPurpose.REPUBLISH);
        if (!decision.allowed(AccessPurpose.REPUBLISH)) {
            throw new BusinessException("RESEARCH_REPORT_SOURCE_REVOKED",
                    "研究报告来源已删除或依赖失效：" + decision.status() + "/" + decision.reasonCode(),
                    org.springframework.http.HttpStatus.CONFLICT);
        }
    }

    /** NR20：既有报告来源是否仍可作为有效保存结果返回（不可用时走替换流程而非直接失败）。 */
    private boolean existingReportSourceUsable(String workspaceId, String sourceId) {
        try {
            requireExistingReportSourceUsable(workspaceId, sourceId);
            return true;
        } catch (BusinessException revoked) {
            return false;
        }
    }

    /**
     * 报告 Markdown 正式下载：权限与依赖闭包都通过后才下发字节。
     * 前端不再从缓存的正文 Blob 生成正式导出，统一走本入口。
     */
    public ResearchReportDownload downloadReportMarkdown(String workspaceId, String researchRunId) {
        requireWorkspace(workspaceId);
        // 下载与详情等价：上下文已脱敏时不能经下载入口绕过正文门控取得原文
        requireFrozenContext(researchRunId);
        SaveReportRow row = loadSaveReportRow(workspaceId, researchRunId);
        if (row.finalReportMarkdown() == null || row.finalReportMarkdown().isBlank()) {
            throw new BusinessException("RESEARCH_REPORT_NOT_READY",
                    "Research report is not complete and cannot be downloaded");
        }
        // COMPLIANCE 撤销或内部缺失的依赖会在这里以结构化原因阻断正文下发
        requireReportDependenciesReadable(workspaceId, researchRunId);
        String fileName = sanitize(reportTitle(row)) + ".md";
        return new ResearchReportDownload(fileName, "text/markdown; charset=UTF-8",
                row.finalReportMarkdown().getBytes(StandardCharsets.UTF_8));
    }

    /** 文件准备段：内容摘要、file_object 登记与报告文件写入都不在长数据库事务内。 */
    private PreparedReportSource prepareReportSource(String workspaceId, String researchRunId, SaveReportRow row) {
        byte[] reportBytes = row.finalReportMarkdown().getBytes(StandardCharsets.UTF_8);
        String contentHash = sha256(reportBytes);
        FileObjectRef fileObject = getOrCreateGeneratedFileObject(
                workspaceId, researchRunId, row.finalReportTitle(), contentHash, reportBytes);
        ResearchReportFileResponse reportFile = writeResearchReportFile(workspaceId, researchRunId, reportBytes);
        return new PreparedReportSource(
                Ids.newId(), Ids.newId(), fileObject, reportFile.objectKey(), contentHash, reportBytes,
                reportTitle(row));
    }

    /** 提交段：短事务内先 CAS 认领 report_source_id，再插入来源与快照并复核依赖。 */
    private SaveResearchReportSourceResponse commitPreparedReportSource(
            String workspaceId, String researchRunId, PreparedReportSource prepared) {
        if (transactionTemplate == null) {
            // 兼容无事务管理器的测试构造：单线程下直接执行同样逻辑
            return doCommitPreparedReportSource(workspaceId, researchRunId, prepared);
        }
        return transactionTemplate.execute(status ->
                doCommitPreparedReportSource(workspaceId, researchRunId, prepared));
    }

    private SaveResearchReportSourceResponse doCommitPreparedReportSource(
            String workspaceId, String researchRunId, PreparedReportSource prepared) {
        {
            SaveReportRow row = loadSaveReportRow(workspaceId, researchRunId);
            String previousDeletedSourceId = null;
            if (row.reportSourceId() != null && !row.reportSourceId().isBlank()) {
                // 并发赢家已发布：可用即返回；已删除（NR20）则尝试替换为新来源
                if (existingReportSourceUsable(workspaceId, row.reportSourceId())) {
                    return loadSavedReportSource(workspaceId, row.reportSourceId());
                }
                previousDeletedSourceId = row.reportSourceId();
            }
            // 文件准备期间依赖可能已被删除；提交前再次检查
            requireReportDependenciesReadable(workspaceId, researchRunId);
            // NR20：CAS 追加「当前值仍为已删除的旧来源」分支——替换登记原子完成；
            // 并发替换输家回读赢家并按可用性返回/报错，不产生双重登记
            int claimed = jdbcTemplate.update("""
                    update research_run
                    set report_source_id = ?, updated_at = current_timestamp
                    where id = ? and workspace_id = ?
                          and (report_source_id is null or report_source_id = ''
                               or report_source_id = ?)
                    """, prepared.sourceId(), researchRunId, workspaceId, previousDeletedSourceId);
            if (claimed != 1) {
                String winnerSourceId = loadSaveReportRow(workspaceId, researchRunId).reportSourceId();
                requireExistingReportSourceUsable(workspaceId, winnerSourceId);
                return loadSavedReportSource(workspaceId, winnerSourceId);
            }
            // 只有 CAS 赢家在同一提交事务内增加引用；输家与崩溃的 prepare 不留计数
            acquireReportFileReference(workspaceId, prepared.fileObject().id());
            jdbcTemplate.update("""
                    insert into source(
                        id, workspace_id, file_object_id, title, source_type, status,
                        parse_status, index_status, generated_by, generated_ref_id, created_by, updated_by
                    )
                    values (?, ?, ?, ?, 'GENERATED_RESEARCH_REPORT', 'PROCESSING', 'PENDING', 'PENDING',
                            'research_agent', ?, 'SYSTEM:RESEARCH', 'SYSTEM:RESEARCH')
                    """, prepared.sourceId(), workspaceId, prepared.fileObject().id(),
                    prepared.title(), researchRunId);
            jdbcTemplate.update("""
                    insert into source_snapshot(
                        id, source_id, file_object_id, version_no, object_key, sha256, parse_status, index_status
                    ) values (?, ?, ?, 1, ?, ?, 'PENDING', 'PENDING')
                    """, prepared.snapshotId(), prepared.sourceId(), prepared.fileObject().id(),
                    prepared.reportObjectKey(), prepared.contentHash());
            sourceCatalogVersionService.bump(workspaceId);
            insertTrace(researchRunId, "REPORT_SAVED_AS_SOURCE", prepared.title(), Map.of(
                    "source_id", prepared.sourceId(),
                    "generated_by", "research_agent"
            ));
            return loadSavedReportSource(workspaceId, prepared.sourceId());
        }
    }

    public ResearchEvidenceManifestResponse evidenceManifest(String workspaceId, String researchRunId) {
        requireWorkspace(workspaceId);
        requireResearchRun(workspaceId, researchRunId);
        requireFrozenContext(researchRunId);
        ResearchEvidenceManifestResponse header = jdbcTemplate.query("""
                select id, report_content_hash, created_at
                from research_evidence_manifest
                where workspace_id = ? and research_run_id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException(
                        "RESEARCH_EVIDENCE_MANIFEST_NOT_FOUND",
                        "Research evidence manifest does not exist"
                );
            }
            return new ResearchEvidenceManifestResponse(
                    rs.getString("id"), researchRunId, rs.getString("report_content_hash"), List.of(),
                    rs.getTimestamp("created_at").toInstant());
        }, workspaceId, researchRunId);
        List<ResearchEvidenceManifestResponse.Evidence> evidence = jdbcTemplate.query("""
                select rank_no, evidence_id, source_id, source_snapshot_id, passage_id,
                       title, excerpt, content_hash, location_info
                from research_evidence_manifest_item
                where manifest_id = ?
                order by rank_no asc
                """, (rs, rowNum) -> new ResearchEvidenceManifestResponse.Evidence(
                rs.getInt("rank_no"), rs.getString("evidence_id"), rs.getString("source_id"),
                rs.getString("source_snapshot_id"), rs.getString("passage_id"), rs.getString("title"),
                rs.getString("excerpt"), rs.getString("content_hash"), rs.getString("location_info")
        ), header.manifestId());
        return new ResearchEvidenceManifestResponse(
                header.manifestId(), header.runId(), header.reportContentHash(), evidence, header.createdAt());
    }

    public Map<String, SaveResearchReportSourceResponse> loadSavedReportSources(
            String workspaceId,
            List<String> sourceIds
    ) {
        List<String> distinctSourceIds = sourceIds.stream().distinct().toList();
        if (distinctSourceIds.isEmpty()) {
            return Map.of();
        }
        List<Object> parameters = new java.util.ArrayList<>();
        parameters.add(workspaceId);
        parameters.addAll(distinctSourceIds);
        return jdbcTemplate.query("""
                select id, title, source_type, status, parse_status, index_status,
                       coalesce(generated_by, '') as generated_by,
                       coalesce(generated_ref_id, '') as generated_ref_id
                from source
                where workspace_id = ? and id in (%s)
                """.formatted(placeholders(distinctSourceIds.size())), rs -> {
            LinkedHashMap<String, SaveResearchReportSourceResponse> results = new LinkedHashMap<>();
            while (rs.next()) {
                SaveResearchReportSourceResponse source = mapSavedReportSource(rs);
                results.put(source.sourceId(), source);
            }
            return results;
        }, parameters.toArray());
    }

    public SaveResearchReportSourceResponse loadSavedReportSource(String workspaceId, String sourceId) {
        return jdbcTemplate.query("""
                select id, title, source_type, status, parse_status, index_status,
                       coalesce(generated_by, '') as generated_by,
                       coalesce(generated_ref_id, '') as generated_ref_id
                from source
                where workspace_id = ? and id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("SOURCE_NOT_FOUND", "Source does not exist");
            }
            return mapSavedReportSource(rs);
        }, workspaceId, sourceId);
    }

    public RunArtifactView loadRunArtifactView(String workspaceId, String researchRunId) {
        requireFrozenContext(researchRunId);
        SaveReportRow row = loadSaveReportRow(workspaceId, researchRunId);
        SaveResearchReportSourceResponse savedReportSource = blankToNull(row.reportSourceId()) == null
                ? null
                : loadSavedReportSource(workspaceId, row.reportSourceId());
        // 失效依赖只保留历史元数据视图：正文文件信息不再下发，COMPLIANCE 不可经文件入口绕过
        ResearchReportFileResponse reportFile = reportBodyAllowed(workspaceId, researchRunId)
                ? buildReportFileResponse(workspaceId, researchRunId, row.finalReportMarkdown())
                : null;
        ResearchRunArtifactResponse researchArtifact = buildResearchArtifact(
                researchRunId, row.finalReportTitle(), null, reportFile, savedReportSource);
        return new RunArtifactView(reportFile, researchArtifact, savedReportSource);
    }

    /** 报告正文是否仍可下发；无门控依赖时保持既有行为。 */
    private boolean reportBodyAllowed(String workspaceId, String researchRunId) {
        return reportDependencyGate == null
                || reportDependencyGate.reportBodyAllowed(workspaceId, researchRunId);
    }

    private void requireFrozenContext(String researchRunId) {
        if (contextGate != null) contextGate.requireReadable(researchRunId);
    }

    /** 把报告保存为来源或导出产物前，确认依赖的来源仍然可读；普通读取保留脱敏后的历史视图。 */
    public void requireReportDependenciesReadable(String workspaceId, String researchRunId) {
        if (reportDependencyGate != null) {
            reportDependencyGate.requireReportDependenciesReadable(workspaceId, researchRunId);
        }
    }

    public ResearchReportFileResponse buildReportFileResponse(
            String workspaceId,
            String researchRunId,
            String reportMarkdown
    ) {
        if (reportMarkdown == null || reportMarkdown.isBlank()) {
            return null;
        }
        byte[] reportBytes = reportMarkdown.getBytes(StandardCharsets.UTF_8);
        return new ResearchReportFileResponse(
                researchReportObjectKey(workspaceId, researchRunId),
                "final.md",
                "text/markdown",
                reportBytes.length,
                sha256(reportBytes)
        );
    }

    public ResearchRunArtifactResponse buildResearchArtifact(
            String researchRunId,
            String finalReportTitle,
            ResearchArtifactCandidateResponse candidate,
            ResearchReportFileResponse reportFile,
            SaveResearchReportSourceResponse savedReportSource
    ) {
        if (candidate == null && reportFile == null && savedReportSource == null) {
            return null;
        }
        return new ResearchRunArtifactResponse(
                researchRunId,
                candidate == null || candidate.artifactType().isBlank()
                        ? "DEEP_RESEARCH_REPORT" : candidate.artifactType(),
                candidate == null || candidate.artifactVersion().isBlank()
                        ? "v1" : candidate.artifactVersion(),
                artifactTitle(finalReportTitle, candidate, researchRunId),
                candidate == null || candidate.generatedBy().isBlank()
                        ? "research_agent" : candidate.generatedBy(),
                candidate == null || candidate.generatedRefType().isBlank()
                        ? "RESEARCH_RUN" : candidate.generatedRefType(),
                candidate == null || candidate.generatedRefId().isBlank()
                        ? researchRunId : candidate.generatedRefId(),
                candidate == null ? 0 : candidate.citationCount(),
                reportFile,
                savedReportSource
        );
    }

    private SaveReportRow loadSaveReportRow(String workspaceId, String researchRunId) {
        return jdbcTemplate.query("""
                select id, workspace_id, status, final_report_title, final_report_markdown, report_source_id
                from research_run
                where workspace_id = ? and id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("RESEARCH_RUN_NOT_FOUND", "Research run does not exist");
            }
            return new SaveReportRow(
                    rs.getString("id"), rs.getString("workspace_id"), rs.getString("status"),
                    rs.getString("final_report_title"), rs.getString("final_report_markdown"),
                    rs.getString("report_source_id")
            );
        }, workspaceId, researchRunId);
    }

    private SaveResearchReportSourceResponse mapSavedReportSource(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new SaveResearchReportSourceResponse(
                rs.getString("id"), rs.getString("title"), rs.getString("source_type"),
                rs.getString("status"), rs.getString("parse_status"), rs.getString("index_status"),
                rs.getString("generated_by"), rs.getString("generated_ref_id")
        );
    }

    private ResearchReportFileResponse writeResearchReportFile(
            String workspaceId,
            String researchRunId,
            byte[] reportBytes
    ) {
        String objectKey = researchReportObjectKey(workspaceId, researchRunId);
        storage.write("noteweave-source", objectKey, reportBytes);
        return new ResearchReportFileResponse(
                objectKey, "final.md", "text/markdown", reportBytes.length, sha256(reportBytes));
    }

    private FileObjectRef getOrCreateGeneratedFileObject(
            String workspaceId,
            String researchRunId,
            String title,
            String contentHash,
            byte[] bytes
    ) {
        if (fileObjectLifecycleService != null) {
            // NR13：prepare 走统一生命周期状态决策（零引用）——DELETED 换新 key 换代后
            // 提交 acquire 不再被拒，相同内容可再次保存；DELETE_CLAIMED 明确待重试；
            // 不再裸复用待删除行让旧票据误删新来源对象
            String freshKey = "workspace/%s/file_object/%s-%s.md".formatted(
                    workspaceId, java.util.UUID.randomUUID().toString().replace("-", ""),
                    sanitize(title));
            com.noteweave.storage.FileObjectLifecycleService.PrepareResult prepared =
                    fileObjectLifecycleService.prepareOrAcquire(
                            workspaceId, contentHash, bytes.length, "text/markdown", freshKey);
            if (prepared.activated()) {
                storage.write("noteweave-source", prepared.objectKey(), bytes);
            } else if (!storage.exists("noteweave-source", prepared.objectKey())) {
                storage.write("noteweave-source", prepared.objectKey(), bytes);
            }
            return new FileObjectRef(prepared.fileObjectId(), prepared.objectKey());
        }
        List<FileObjectRef> existing = jdbcTemplate.query("""
                select id, object_key from file_object where workspace_id = ? and sha256 = ?
                """, (rs, rowNum) -> new FileObjectRef(
                rs.getString("id"), rs.getString("object_key")), workspaceId, contentHash);
        // prepare 只准备对象与摘要，不产生永久引用增量：引用由 CAS 赢家在提交事务内增加
        if (!existing.isEmpty()) {
            FileObjectRef ref = existing.get(0);
            if (!storage.exists("noteweave-source", ref.objectKey())) {
                storage.write("noteweave-source", ref.objectKey(), bytes);
            }
            return ref;
        }
        String fileObjectId = Ids.newId();
        String objectKey = "workspace/%s/file_object/%s-%s.md"
                .formatted(workspaceId, contentHash, sanitize(title));
        storage.write("noteweave-source", objectKey, bytes);
        jdbcTemplate.update("""
                insert into file_object(id, workspace_id, object_key, sha256, file_size, mime_type, ref_count)
                values (?, ?, ?, ?, ?, 'text/markdown', 0)
                """, fileObjectId, workspaceId, objectKey, contentHash, bytes.length);
        return new FileObjectRef(fileObjectId, objectKey);
    }

    /** 提交事务内的引用获取；网关未接线时执行既有内联 SQL。 */
    private void acquireReportFileReference(String workspaceId, String fileObjectId) {
        if (fileObjectLifecycle != null) {
            fileObjectLifecycle.acquire(workspaceId, fileObjectId);
            return;
        }
        jdbcTemplate.update("update file_object set ref_count = ref_count + 1 where id = ?",
                fileObjectId);
    }

    private void insertTrace(String researchRunId, String type, String message, Map<String, Object> payload) {
        jdbcTemplate.update("""
                insert into research_trace(id, research_run_id, trace_type, trace_message, payload_json)
                values (?, ?, ?, ?, ?)
                """, Ids.newId(), researchRunId, type, message, Json.write(objectMapper, payload));
    }

    private void requireWorkspace(String workspaceId) {
        if (!workspaceService.exists(workspaceId)) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "Workspace does not exist");
        }
    }

    private void requireResearchRun(String workspaceId, String researchRunId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from research_run where workspace_id = ? and id = ?
                """, Integer.class, workspaceId, researchRunId);
        if (count == null || count <= 0) {
            throw new BusinessException("RESEARCH_RUN_NOT_FOUND", "Research run does not exist");
        }
    }

    private String artifactTitle(
            String finalReportTitle,
            ResearchArtifactCandidateResponse candidate,
            String researchRunId
    ) {
        if (candidate != null && !candidate.title().isBlank()) {
            return candidate.title();
        }
        if (finalReportTitle != null && !finalReportTitle.isBlank()) {
            return finalReportTitle;
        }
        return "Research Report " + researchRunId;
    }

    private String reportTitle(SaveReportRow row) {
        return row.finalReportTitle() == null || row.finalReportTitle().isBlank()
                ? "Research Report " + row.researchRunId()
                : row.finalReportTitle();
    }

    private String researchReportObjectKey(String workspaceId, String researchRunId) {
        return "workspace/%s/research/%s/report/final.md".formatted(workspaceId, researchRunId);
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private String sanitize(String value) {
        String normalized = value == null ? "research-report" : value;
        return normalized.replaceAll("[^\\p{IsHan}a-zA-Z0-9._-]+", "-");
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    public record RunArtifactView(
            ResearchReportFileResponse reportFile,
            ResearchRunArtifactResponse researchArtifact,
            SaveResearchReportSourceResponse savedReportSource
    ) {
    }

    private record SaveReportRow(
            String researchRunId,
            String workspaceId,
            String status,
            String finalReportTitle,
            String finalReportMarkdown,
            String reportSourceId
    ) {
    }

    private record FileObjectRef(String id, String objectKey) {
    }

    /** prepare 段产物：内容与文件在提交前已就绪，提交段只做数据库写入。 */
    private record PreparedReportSource(
            String sourceId,
            String snapshotId,
            FileObjectRef fileObject,
            String reportObjectKey,
            String contentHash,
            byte[] reportBytes,
            String title
    ) {
    }

    /** 报告 Markdown 下载内容；文件名已做安全处理。 */
    public record ResearchReportDownload(String fileName, String contentType, byte[] content) {
    }
}
