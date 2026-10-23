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
            ResearchGeneratedSourceReadGate researchGeneratedSourceReadGate
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
     * 删除资料并记录删除原因。
     *
     * <p>USER 表示用户主动删除，历史笔记继续保留；COMPLIANCE 表示合规删除，
     * 引用该资料的笔记会退出可用状态，但笔记内容和审计信息不做物理删除。</p>
     */
    @Transactional
    public DeleteSourceResponse deleteSource(String workspaceId, String sourceId, String reason) {
        String deleteReason = parseDeleteReason(reason);
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.SOURCE_WRITE);
        String actor = auditActorProvider.currentOrSystem("SOURCE");
        SourceRef source = loadSource(workspaceId, sourceId);
        LinkedHashSet<String> cleanupObjectKeys = new LinkedHashSet<>(loadSnapshotObjectKeys(sourceId));
        if (DELETE_REASON_COMPLIANCE.equals(deleteReason)) {
            // 合规删除显式写入原因；USER 使用字段默认值，历史删除记录同样按 USER 解释
            jdbcTemplate.update("""
                    update source
                    set status = 'DELETED', parse_status = 'DELETED', index_status = 'DELETED',
                        delete_reason = ?, updated_by = ?, updated_at = current_timestamp
                    where workspace_id = ? and id = ?
                    """, deleteReason, actor, workspaceId, sourceId);
        } else {
            jdbcTemplate.update("""
                    update source
                    set status = 'DELETED', parse_status = 'DELETED', index_status = 'DELETED',
                        updated_by = ?, updated_at = current_timestamp
                    where workspace_id = ? and id = ?
                    """, actor, workspaceId, sourceId);
        }
        jdbcTemplate.update("""
                update source_snapshot
                set parse_status = 'DELETED', index_status = 'DELETED'
                where source_id = ?
                """, sourceId);
        if (DELETE_REASON_COMPLIANCE.equals(deleteReason)) {
            revokeNotesReferencingSource(workspaceId, sourceId, actor);
        }
        runReplayRedactionService.redactDeletedSource(workspaceId, sourceId);
        cancelPendingSourceTasks(workspaceId, sourceId);
        if (releaseFileObject(source.fileObjectId())) cleanupObjectKeys.add(source.objectKey());
        sourceCatalogVersionService.bump(workspaceId);
        String wikiTaskId = wikiCommandPort.requestSourceRetract(workspaceId, sourceId, source.title());
        eventPublisher.publishEvent(new SourceDeletedEvent(
                workspaceId, sourceId, List.copyOf(cleanupObjectKeys), deleteReason));
        return new DeleteSourceResponse(sourceId, "DELETED", wikiTaskId);
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

    private boolean releaseFileObject(String fileObjectId) {
        jdbcTemplate.update("""
                update file_object
                set ref_count = case when ref_count > 0 then ref_count - 1 else 0 end
                where id = ?
                """, fileObjectId);
        Integer remaining = jdbcTemplate.queryForObject(
                "select ref_count from file_object where id = ?", Integer.class, fileObjectId);
        return remaining != null && remaining == 0;
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

    private SourceRef loadSource(String workspaceId, String sourceId) {
        return jdbcTemplate.query("""
                select s.id, s.title, s.status, s.file_object_id, f.object_key
                from source s
                join file_object f on f.id = s.file_object_id
                where s.workspace_id = ? and s.id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("SOURCE_NOT_FOUND", "资料不存在");
            }
            if ("DELETED".equals(rs.getString("status"))) {
                throw new BusinessException("SOURCE_ALREADY_DELETED", "资料已经删除");
            }
            return new SourceRef(
                    rs.getString("id"),
                    rs.getString("title"),
                    rs.getString("file_object_id"),
                    rs.getString("object_key"));
        }, workspaceId, sourceId);
    }

    private Instant toInstant(Timestamp value) {
        return value == null ? Instant.now() : value.toInstant();
    }

    private record SourceRef(String sourceId, String title, String fileObjectId, String objectKey) {
    }
}
