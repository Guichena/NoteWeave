package com.noteweave.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;
import com.noteweave.storage.ObjectStorage;
import com.noteweave.task.TaskCommandPort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单个资料的重新处理。
 * <p>
 * 解析或切片失败时从解析阶段重新开始；解析已完成、只有检索索引失败时从向量化阶段重新开始，
 * 已有的片段保持不变。检索索引的自动重试也复用这里的逻辑。
 */
@Service
public class SourceReprocessService {

    private static final String BUCKET_SOURCE = "noteweave-source";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final TaskCommandPort taskCommandPort;
    private final SourceMessagingMode messagingMode;
    private final SourceParsePort sourceParsePort;
    private final ObjectStorage storage;
    private final SourceCatalogVersionService sourceCatalogVersionService;
    private final ApplicationEventPublisher eventPublisher;

    public SourceReprocessService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                  WorkspaceAccessGuard workspaceAccessGuard, TaskCommandPort taskCommandPort,
                                  SourceMessagingMode messagingMode, SourceParsePort sourceParsePort,
                                  ObjectStorage storage, SourceCatalogVersionService sourceCatalogVersionService,
                                  ApplicationEventPublisher eventPublisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.taskCommandPort = taskCommandPort;
        this.messagingMode = messagingMode;
        this.sourceParsePort = sourceParsePort;
        this.storage = storage;
        this.sourceCatalogVersionService = sourceCatalogVersionService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public SourceReprocessResponse reprocess(String workspaceId, String sourceId) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.SOURCE_WRITE);
        List<Snapshot> rows = jdbcTemplate.query("""
                select s.status, ss.id, ss.parse_status, ss.index_status, ss.object_key
                from source s
                join source_snapshot ss on ss.source_id = s.id
                where s.id = ? and s.workspace_id = ? and s.status <> 'DELETED'
                order by ss.version_no desc
                limit 1
                for update
                """, (rs, rowNum) -> new Snapshot(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5)), sourceId, workspaceId);
        if (rows.isEmpty()) {
            throw new BusinessException("SOURCE_NOT_FOUND", "资料不存在", HttpStatus.NOT_FOUND);
        }
        Snapshot snapshot = rows.get(0);
        if ("FAILED".equals(snapshot.parseStatus())) {
            String taskId = restartParse(workspaceId, sourceId, snapshot);
            return new SourceReprocessResponse(sourceId, taskId, "PARSE");
        }
        if ("PARSED".equals(snapshot.parseStatus()) && "FAILED".equals(snapshot.indexStatus())) {
            String taskId = requeueIndexing(workspaceId, sourceId, snapshot.snapshotId(),
                    "重新处理：重新生成检索索引", false);
            if (taskId == null) {
                throw new BusinessException("SOURCE_REPROCESS_CONFLICT", "资料状态已变化，请刷新后重试",
                        HttpStatus.CONFLICT);
            }
            return new SourceReprocessResponse(sourceId, taskId, "INDEX");
        }
        throw new BusinessException("SOURCE_REPROCESS_NOT_NEEDED", "资料没有处理失败，无需重新处理",
                HttpStatus.CONFLICT);
    }

    /**
     * 从向量化阶段重新生成检索索引，需要在事务中调用。快照不再处于索引失败状态时返回 null。
     * automatic 为 true 时计入自动重试次数，手动重新处理则清零。
     */
    public String requeueIndexing(String workspaceId, String sourceId, String snapshotId, String reason,
                                  boolean automatic) {
        int snapshotMoved = jdbcTemplate.update("""
                update source_snapshot
                set index_status = 'INDEXING', processing_stage = ?, next_index_retry_at = null,
                    index_attempt_count = case when ? then index_attempt_count + 1 else 0 end
                where id = ? and source_id = ? and parse_status = 'PARSED' and index_status = 'FAILED'
                """, SourcePipelineStages.STAGE_EMBEDDING, automatic, snapshotId, sourceId);
        if (snapshotMoved != 1) {
            return null;
        }
        int sourceMoved = jdbcTemplate.update("""
                update source
                set index_status = 'INDEXING', status = 'PROCESSING',
                    updated_by = 'SYSTEM:SOURCE_REPROCESS', updated_at = current_timestamp
                where id = ? and workspace_id = ? and status = 'FAILED' and index_status = 'FAILED'
                """, sourceId, workspaceId);
        if (sourceMoved != 1) {
            throw new BusinessException("SOURCE_REPROCESS_CONFLICT", "资料状态已变化，请刷新后重试",
                    HttpStatus.CONFLICT);
        }
        String taskId = taskCommandPort.createTask(workspaceId, "SOURCE_PARSE", "SOURCE", sourceId,
                SourcePipelineStages.STAGE_EMBEDDING, reason);
        taskCommandPort.startTask(taskId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("workspaceId", workspaceId);
        payload.put("sourceId", sourceId);
        payload.put("sourceSnapshotId", snapshotId);
        String outboxId = Ids.newId();
        boolean async = messagingMode.isAsyncEnabled();
        jdbcTemplate.update("""
                insert into task_outbox(id, task_id, topic, message_key, payload_json, status)
                values (?, ?, ?, ?, ?, 'READY')
                """, outboxId, taskId, async ? SourcePipelineStages.TOPIC_EMBED : "noteweave.retrieval.projection",
                snapshotId, Json.write(objectMapper, payload));
        if (!async) {
            eventPublisher.publishEvent(new SourceParseService.SynchronousRetrievalProjectionRequested(
                    outboxId, taskId, workspaceId, sourceId, snapshotId));
        }
        sourceCatalogVersionService.bump(workspaceId);
        return taskId;
    }

    private String restartParse(String workspaceId, String sourceId, Snapshot snapshot) {
        // 解析失败时事务已回滚，一般没有残留片段；这里仍然清理一次，保证重新切片从空状态开始
        jdbcTemplate.update("""
                delete from source_window where source_chunk_id in (
                    select id from source_chunk where source_snapshot_id = ?)
                """, snapshot.snapshotId());
        jdbcTemplate.update("delete from source_chunk where source_snapshot_id = ?", snapshot.snapshotId());
        jdbcTemplate.update("""
                update source_snapshot
                set parse_status = 'PENDING', index_status = 'PENDING', processing_stage = null,
                    index_attempt_count = 0, next_index_retry_at = null
                where id = ?
                """, snapshot.snapshotId());
        jdbcTemplate.update("""
                update source
                set parse_status = 'PENDING', index_status = 'PENDING', status = 'PROCESSING',
                    updated_by = 'SYSTEM:SOURCE_REPROCESS', updated_at = current_timestamp
                where id = ? and workspace_id = ?
                """, sourceId, workspaceId);
        String taskId = taskCommandPort.createTask(workspaceId, "SOURCE_PARSE", "SOURCE", sourceId,
                "PARSING", "重新处理：资料解析与切片");
        if (messagingMode.isAsyncEnabled()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("taskId", taskId);
            payload.put("sourceId", sourceId);
            payload.put("snapshotId", snapshot.snapshotId());
            payload.put("workspaceId", workspaceId);
            jdbcTemplate.update("""
                    insert into task_outbox(id, task_id, topic, message_key, payload_json, status)
                    values (?, ?, ?, ?, ?, 'READY')
                    """, Ids.newId(), taskId, SourcePipelineStages.TOPIC_PARSE, sourceId,
                    Json.write(objectMapper, payload));
        } else {
            taskCommandPort.startTask(taskId);
            sourceParsePort.parseAndIndex(workspaceId, sourceId, snapshot.snapshotId(),
                    storage.read(BUCKET_SOURCE, snapshot.objectKey()));
        }
        sourceCatalogVersionService.bump(workspaceId);
        return taskId;
    }

    private record Snapshot(String sourceStatus, String snapshotId, String parseStatus, String indexStatus,
                            String objectKey) {
    }
}
