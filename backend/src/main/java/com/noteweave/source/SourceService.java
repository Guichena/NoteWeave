package com.noteweave.source;

import com.noteweave.common.BusinessException;
import com.noteweave.conversation.RunReplayRedactionService;
import com.noteweave.research.ResearchGeneratedSourceReadGate;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;
import com.noteweave.security.AuditActorProvider;
import com.noteweave.task.TaskCommandPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceService {

    public static final String DELETE_REASON_USER = "USER";
    public static final String DELETE_REASON_COMPLIANCE = "COMPLIANCE";

    private static final ObjectMapper METADATA_READER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final SourceWikiCommandPort wikiCommandPort;
    private final AuditActorProvider auditActorProvider;
    private final SourceCatalogCache sourceCatalogCache;
    private final SourceCatalogVersionService sourceCatalogVersionService;
    private final MeterRegistry meterRegistry;
    private final TaskCommandPort taskCommandPort;
    private final ApplicationEventPublisher eventPublisher;
    private final RunReplayRedactionService runReplayRedactionService;
    private final ResearchGeneratedSourceReadGate researchGeneratedSourceReadGate;
    private final SourceCompliancePurgeService compliancePurgeService;

    /** 文件对象生命周期网关；未注入时保留既有内联引用 SQL（legacy 路径）。 */
    private com.noteweave.source.storage.FileObjectLifecycleGateway fileObjectLifecycle;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setFileObjectLifecycle(com.noteweave.source.storage.FileObjectLifecycleGateway fileObjectLifecycle) {
        this.fileObjectLifecycle = fileObjectLifecycle;
    }

    /**
     * 派生对象生命周期设施（B 侧，AU-001 接线）：删除事务内登记派生清理意图。
     * 未注入时（直连构造的测试）跳过——过期登记对账与孤儿清理仍兜底可见性。
     */
    private com.noteweave.storage.FileObjectLifecycleService derivedObjectLifecycle;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDerivedObjectLifecycle(com.noteweave.storage.FileObjectLifecycleService derivedObjectLifecycle) {
        this.derivedObjectLifecycle = derivedObjectLifecycle;
    }

    public SourceService(
            JdbcTemplate jdbcTemplate,
            WorkspaceAccessGuard workspaceAccessGuard,
            SourceWikiCommandPort wikiCommandPort,
            AuditActorProvider auditActorProvider,
            SourceCatalogCache sourceCatalogCache,
            SourceCatalogVersionService sourceCatalogVersionService,
            MeterRegistry meterRegistry,
            TaskCommandPort taskCommandPort,
            ApplicationEventPublisher eventPublisher,
            RunReplayRedactionService runReplayRedactionService,
            ResearchGeneratedSourceReadGate researchGeneratedSourceReadGate,
            SourceCompliancePurgeService compliancePurgeService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.wikiCommandPort = wikiCommandPort;
        this.auditActorProvider = auditActorProvider;
        this.sourceCatalogCache = sourceCatalogCache;
        this.sourceCatalogVersionService = sourceCatalogVersionService;
        this.meterRegistry = meterRegistry;
        this.taskCommandPort = taskCommandPort;
        this.eventPublisher = eventPublisher;
        this.runReplayRedactionService = runReplayRedactionService;
        this.researchGeneratedSourceReadGate = researchGeneratedSourceReadGate;
        this.compliancePurgeService = compliancePurgeService;
    }

    public List<SourceResponse> listSources(String workspaceId) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.WORKSPACE_READ);
        long catalogVersion = sourceCatalogVersionService.current(workspaceId);
        return sourceCatalogCache.get(workspaceId, catalogVersion)
                .orElseGet(() -> loadSources(workspaceId, catalogVersion)).stream()
                .filter(source -> researchGeneratedSourceReadGate.visible(
                        workspaceId, source.generatedBy(), source.generatedRefId()))
                .toList();
    }

    private List<SourceResponse> loadSources(String workspaceId, long catalogVersion) {
        long startedAt = System.nanoTime();
        List<SourceResponse> sources;
        try {
            Map<String, String> parseTaskIds = loadLatestParseTaskIds(workspaceId);
            Map<String, SnapshotStage> stages = loadLatestSnapshotStages(workspaceId);
            sources = jdbcTemplate.query("""
                    select id, title, source_type, status, parse_status, index_status,
                           coalesce(generated_by, '') as generated_by,
                           coalesce(generated_ref_id, '') as generated_ref_id,
                           updated_at, metadata_json
                    from source
                    where workspace_id = ? and status <> 'DELETED'
                    order by updated_at desc, id desc
                    """, (rs, rowNum) -> {
                JsonNode metadata = readMetadata(rs.getString("metadata_json"));
                String sourceId = rs.getString("id");
                SnapshotStage stage = stages.getOrDefault(sourceId, SnapshotStage.NONE);
                return new SourceResponse(
                        sourceId,
                        rs.getString("title"),
                        rs.getString("source_type"),
                        rs.getString("status"),
                        rs.getString("parse_status"),
                        rs.getString("index_status"),
                        rs.getString("generated_by"),
                        rs.getString("generated_ref_id"),
                        toInstant(rs.getTimestamp("updated_at")),
                        readCount(metadata, "chunk_count"),
                        readCount(metadata, "page_count"),
                        parseTaskIds.get(sourceId),
                        stage.stage(),
                        stage.attempts(),
                        stage.nextRetryAt()
                );
            }, workspaceId);
        } catch (RuntimeException exception) {
            recordDatabaseLoad("error", startedAt);
            throw exception;
        }
        recordDatabaseLoad("success", startedAt);
        sourceCatalogCache.put(workspaceId, catalogVersion, sources);
        return sources;
    }

    /** 每个资料最新快照的处理阶段与自动重试信息。 */
    private Map<String, SnapshotStage> loadLatestSnapshotStages(String workspaceId) {
        Map<String, SnapshotStage> stages = new HashMap<>();
        jdbcTemplate.query("""
                select ss.source_id, ss.processing_stage, ss.index_attempt_count, ss.next_index_retry_at
                from source_snapshot ss
                join source s on s.id = ss.source_id
                where s.workspace_id = ? and s.status <> 'DELETED'
                  and ss.version_no = (select max(x.version_no) from source_snapshot x where x.source_id = ss.source_id)
                """, (RowCallbackHandler) rs -> stages.put(rs.getString(1), new SnapshotStage(
                rs.getString(2), rs.getInt(3),
                // 没有安排自动重试时保持为空，不能套用 updated_at 的缺省值
                rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant())), workspaceId);
        return stages;
    }

    private record SnapshotStage(String stage, Integer attempts, java.time.Instant nextRetryAt) {
        static final SnapshotStage NONE = new SnapshotStage(null, null, null);
    }

    /** 按创建顺序读取工作台内的资料解析任务，同一资料以最后一次为准。 */
    private Map<String, String> loadLatestParseTaskIds(String workspaceId) {
        Map<String, String> taskIds = new HashMap<>();
        jdbcTemplate.query("""
                select id, target_id
                from task
                where workspace_id = ? and target_type = 'SOURCE' and task_type = 'SOURCE_PARSE'
                order by created_at, id
                """, (RowCallbackHandler) rs -> taskIds.put(rs.getString("target_id"), rs.getString("id")),
                workspaceId);
        return taskIds;
    }

    private static JsonNode readMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }
        try {
            return METADATA_READER.readTree(metadataJson);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private static Integer readCount(JsonNode metadata, String field) {
        JsonNode value = metadata == null ? null : metadata.get(field);
        return value != null && value.canConvertToInt() ? value.intValue() : null;
    }

    private void recordDatabaseLoad(String result, long startedAt) {
        meterRegistry.timer("noteweave.source.catalog.db.load", "result", result)
                .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
    }

    /** 兼容原有调用方式，默认按用户主动删除处理。 */
    @Transactional
    public DeleteSourceResponse deleteSource(String workspaceId, String sourceId) {
        return deleteSource(workspaceId, sourceId, DELETE_REASON_USER);
    }

    /**
     * 删除资料并记录删除原因，幂等且允许原因单向升级。
     *
     * <p>USER 表示用户主动删除，历史笔记继续保留；COMPLIANCE 表示合规删除，
     * 引用该资料的笔记会退出可用状态，但笔记内容和审计信息不做物理删除。
     * 重复删除返回既有删除事实，不再释放引用或重复安排清理；
     * USER 删除后可以升级为 COMPLIANCE，COMPLIANCE 不能降级回 USER。</p>
     */
    @Transactional
    public DeleteSourceResponse deleteSource(String workspaceId, String sourceId, String reason) {
        String deleteReason = parseDeleteReason(reason);
        if (DELETE_REASON_COMPLIANCE.equals(deleteReason)) {
            // 合规删除会清除正文并撤销笔记，破坏性远高于普通删除：只允许 OWNER 执行
            workspaceAccessGuard.requireOwner(workspaceId);
        } else {
            workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.SOURCE_WRITE);
        }
        String actor = auditActorProvider.currentOrSystem("SOURCE");
        // FOR UPDATE 锁定后读取：并发相反原因的删除由行锁串行化，后到者看到前者的终态
        SourceRef source = loadSourceForDeletion(workspaceId, sourceId);
        DeleteTransitionDecision transition = DeleteTransitionDecision.decide(
                source.status(), source.deleteReason(), deleteReason);
        switch (transition.kind()) {
            case ALREADY_DELETED -> {
                // 幂等回执：不重复释放引用、不重建 Wiki 撤回任务、不重复安排清理
                recordDeleteOutcome(transition, "idempotent");
                return new DeleteSourceResponse(sourceId, "DELETED", "",
                        transition.kind().name(), transition.effectiveReason());
            }
            case UPGRADE_TO_COMPLIANCE -> {
                jdbcTemplate.update("""
                        update source
                        set delete_reason = 'COMPLIANCE', updated_by = ?, updated_at = current_timestamp
                        where workspace_id = ? and id = ? and status = 'DELETED'
                        """, actor, workspaceId, sourceId);
                revokeNotesReferencingSource(workspaceId, sourceId, actor);
                // 由 USER 升级为 COMPLIANCE 时正文同样必须清除（此前只改原因，正文永久残留）
                compliancePurgeService.purge(workspaceId, sourceId);
                sourceCatalogVersionService.bump(workspaceId);
                // 升级不重复减引用，但补登记可能缺失的物理清理意图（NOT EXISTS 幂等）
                List<String> upgradeCleanupKeys = new java.util.ArrayList<>(loadSnapshotObjectKeys(sourceId));
                if (source.objectKey() != null && !source.objectKey().isBlank()) {
                    upgradeCleanupKeys.add(source.objectKey());
                }
                eventPublisher.publishEvent(SourceDeletedEvent.upgradeToCompliance(
                        workspaceId, sourceId, upgradeCleanupKeys));
                recordDeleteOutcome(transition, "upgraded");
                return new DeleteSourceResponse(sourceId, "DELETED", "",
                        transition.kind().name(), transition.effectiveReason());
            }
            case FIRST_DELETE -> {
                return deleteFirstTime(workspaceId, sourceId, actor, source, transition);
            }
            default -> throw new IllegalStateException("Unknown delete transition: " + transition.kind());
        }
    }

    /** 首次删除：一次性释放原件引用并安排清理，事件携带 transition 供监听器幂等。 */
    private DeleteSourceResponse deleteFirstTime(
            String workspaceId,
            String sourceId,
            String actor,
            SourceRef source,
            DeleteTransitionDecision transition
    ) {
        String deleteReason = transition.effectiveReason();
        LinkedHashSet<String> cleanupObjectKeys = new LinkedHashSet<>(loadSnapshotObjectKeys(sourceId));
        jdbcTemplate.update("""
                update source
                set status = 'DELETED', parse_status = 'DELETED', index_status = 'DELETED',
                    delete_reason = ?, updated_by = ?, updated_at = current_timestamp
                where workspace_id = ? and id = ?
                """, deleteReason, actor, workspaceId, sourceId);
        jdbcTemplate.update("""
                update source_snapshot
                set parse_status = 'DELETED', index_status = 'DELETED'
                where source_id = ?
                """, sourceId);
        if (DELETE_REASON_COMPLIANCE.equals(deleteReason)) {
            revokeNotesReferencingSource(workspaceId, sourceId, actor);
            // 合规删除承诺正文不再残留：同事务清空分块正文、窗口、引用摘录与摘要元数据
            compliancePurgeService.purge(workspaceId, sourceId);
        }
        runReplayRedactionService.redactDeletedSource(workspaceId, sourceId);
        cancelPendingSourceTasks(workspaceId, sourceId);
        // AU-001 / RB-008：派生对象（解析文档/提取正文/转写/向量批次）清理意图与删除同事务
        // 登记 RELEASED；物理删除由 B 的孤儿对账按登记执行，共享对象不受影响
        if (derivedObjectLifecycle != null) {
            int releasedDerived = derivedObjectLifecycle
                    .releaseDerivedForSource(workspaceId, sourceId, null).size();
            if (releasedDerived > 0) {
                meterRegistry.counter("noteweave.source.delete.derived_cleanup_registered").increment();
            }
        }
        // 引用只在首次删除释放一次：重复/升级路径不会再进入这里
        if (releaseFileObject(workspaceId, source.fileObjectId())) cleanupObjectKeys.add(source.objectKey());
        sourceCatalogVersionService.bump(workspaceId);
        String wikiTaskId = wikiCommandPort.requestSourceRetract(workspaceId, sourceId, source.title());
        eventPublisher.publishEvent(new SourceDeletedEvent(
                workspaceId, sourceId, List.copyOf(cleanupObjectKeys), deleteReason,
                SourceDeletedEvent.TRANSITION_FIRST_DELETE));
        recordDeleteOutcome(transition, "deleted");
        return new DeleteSourceResponse(sourceId, "DELETED", wikiTaskId,
                transition.kind().name(), deleteReason);
    }

    /** 删除结果按 transition/reason/outcome 聚合；sourceId 只进结构化日志，避免高基数指标。 */
    private void recordDeleteOutcome(DeleteTransitionDecision transition, String outcome) {
        meterRegistry.counter("noteweave.source.delete",
                        "transition", transition.kind().name().toLowerCase(Locale.ROOT),
                        "reason", transition.effectiveReason().toLowerCase(Locale.ROOT),
                        "outcome", outcome)
                .increment();
    }

    /** 合规删除时让引用该资料的笔记退出可用状态，笔记内容与审计信息保留。 */
    private void revokeNotesReferencingSource(String workspaceId, String sourceId, String actor) {
        jdbcTemplate.update("""
                update knowledge_item
                set status = 'REVOKED', updated_by = ?, updated_at = current_timestamp
                where workspace_id = ?
                  and item_type = 'NOTE'
                  and status = 'ACTIVE'
                  and exists (
                      select 1
                      from knowledge_version_citation kvc
                      join citation c on c.id = kvc.citation_id
                      where kvc.knowledge_version_id = knowledge_item.latest_version_id
                        and c.workspace_id = ?
                        and c.source_id = ?
                  )
                """, actor, workspaceId, workspaceId, sourceId);
    }

    /** 校验删除原因，空值按 USER 处理，非法值返回 400。 */
    public static String parseDeleteReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return DELETE_REASON_USER;
        }
        String normalized = reason.trim();
        if (!DELETE_REASON_USER.equals(normalized) && !DELETE_REASON_COMPLIANCE.equals(normalized)) {
            throw new BusinessException(
                    "SOURCE_DELETE_REASON_INVALID",
                    "删除原因只能是 USER 或 COMPLIANCE");
        }
        return normalized;
    }

    /** 引用释放与删除事务同提交；网关未接线时执行既有内联 SQL。 */
    private boolean releaseFileObject(String workspaceId, String fileObjectId) {
        long remaining = fileObjectLifecycle != null
                ? fileObjectLifecycle.release(workspaceId, fileObjectId)
                : legacyReleaseFileObject(fileObjectId);
        return remaining == 0;
    }

    private long legacyReleaseFileObject(String fileObjectId) {
        jdbcTemplate.update("""
                update file_object
                set ref_count = case when ref_count > 0 then ref_count - 1 else 0 end
                where id = ?
                """, fileObjectId);
        Integer remaining = jdbcTemplate.queryForObject(
                "select ref_count from file_object where id = ?", Integer.class, fileObjectId);
        return remaining == null ? -1 : remaining;
    }

    private List<String> loadSnapshotObjectKeys(String sourceId) {
        return jdbcTemplate.queryForList("""
                select object_key from source_snapshot
                where source_id = ? and object_key is not null and object_key <> ''
                order by version_no, id
                """, String.class, sourceId);
    }

    private void cancelPendingSourceTasks(String workspaceId, String sourceId) {
        List<String> taskIds = jdbcTemplate.queryForList("""
                select id from task
                where workspace_id = ? and target_type = 'SOURCE' and target_id = ?
                  and task_type in ('SOURCE_PARSE', 'WIKI_INGEST')
                  and task_status in ('PENDING', 'RUNNING', 'WAITING')
                """, String.class, workspaceId, sourceId);
        for (String taskId : taskIds) {
            jdbcTemplate.update("""
                    update task_outbox
                    set status = 'CANCELLED', claimed_at = null, lease_owner = null, lease_until = null,
                        last_error = 'SOURCE_DELETED'
                    where task_id = ? and status in ('READY', 'PROCESSING')
                    """, taskId);
            taskCommandPort.cancelTask(taskId, "SOURCE_DELETED", "资料已删除，已取消后续处理", sourceId);
        }
    }

    /**
     * 删除路径专用加载：FOR UPDATE 锁定，允许返回 DELETED 行及其删除原因。
     * 查询始终 workspace 限定；不存在的资料不暴露其他工作台的任何信息。
     */
    private SourceRef loadSourceForDeletion(String workspaceId, String sourceId) {
        return jdbcTemplate.query("""
                select s.id, s.title, s.status, coalesce(s.delete_reason, 'USER') as delete_reason,
                       s.file_object_id, f.object_key
                from source s
                join file_object f on f.id = s.file_object_id
                where s.workspace_id = ? and s.id = ?
                    for update
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("SOURCE_NOT_FOUND", "资料不存在");
            }
            return new SourceRef(
                    rs.getString("id"),
                    rs.getString("title"),
                    rs.getString("file_object_id"),
                    rs.getString("object_key"),
                    rs.getString("status"),
                    rs.getString("delete_reason"));
        }, workspaceId, sourceId);
    }

    private Instant toInstant(Timestamp value) {
        return value == null ? Instant.now() : value.toInstant();
    }

    private record SourceRef(
            String sourceId,
            String title,
            String fileObjectId,
            String objectKey,
            String status,
            String deleteReason
    ) {
    }
}
