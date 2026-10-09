package com.noteweave.retrieval.projection;

import com.noteweave.common.BusinessException;
import com.noteweave.retrieval.projection.RetrievalProjectionRepository.ProjectionType;
import com.noteweave.source.SourceCatalogVersionService;
import com.noteweave.task.TaskService;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceRetrievalProjectionFinalizer {
    private final JdbcTemplate jdbcTemplate;
    private final RetrievalProjectionRepository projectionRepository;
    private final SourceCatalogVersionService sourceCatalogVersionService;
    private final TaskService taskService;

    public SourceRetrievalProjectionFinalizer(
            JdbcTemplate jdbcTemplate,
            RetrievalProjectionRepository projectionRepository,
            SourceCatalogVersionService sourceCatalogVersionService,
            TaskService taskService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.projectionRepository = projectionRepository;
        this.sourceCatalogVersionService = sourceCatalogVersionService;
        this.taskService = taskService;
    }

    public List<StaleSnapshot> staleSnapshots(String workspaceId, String sourceId, String currentSnapshotId) {
        return jdbcTemplate.query("""
                select distinct rp.source_snapshot_id, rp.projection_type, rp.target_index
                from retrieval_projection rp
                join source_snapshot old_ss on old_ss.id = rp.source_snapshot_id
                join source_snapshot current_ss on current_ss.id = ? and current_ss.source_id = old_ss.source_id
                where rp.workspace_id = ? and rp.source_id = ? and rp.source_snapshot_id <> ?
                  and old_ss.version_no < current_ss.version_no and rp.status = 'READY'
                """, (rs, rowNum) -> new StaleSnapshot(
                rs.getString("source_snapshot_id"),
                ProjectionType.valueOf(rs.getString("projection_type")),
                rs.getString("target_index")),
                currentSnapshotId, workspaceId, sourceId, currentSnapshotId);
    }

    @Transactional
    public List<StaleSnapshot> finalizeReady(
            String workspaceId,
            String sourceId,
            String snapshotId,
            String taskId,
            SourceRetrievalProjectionService.ProjectionResult result
    ) {
        if (result == null || !result.complete()) {
            throw new IllegalStateException("Both QA and Note projections must be complete before finalization");
        }
        CurrentSnapshot current = lockCurrentSnapshot(workspaceId, sourceId, snapshotId);
        if (!current.current()) {
            throw new IllegalStateException("Retrieval projection target is not the current source snapshot");
        }
        if (current.ready()) {
            return List.of();
        }
        if (!current.acceptsReadyTransition()) {
            throw new IllegalStateException("Source is not awaiting retrieval projection finalization");
        }
        int expectedChunks = countChunks(workspaceId, sourceId, snapshotId);
        int qaReady = countReady(workspaceId, sourceId, snapshotId, ProjectionType.QA_CHUNK,
                result.embeddingVersion(), SourceRetrievalProjectionService.QA_SCHEMA_VERSION, result.qaIndex());
        int noteReady = countReady(workspaceId, sourceId, snapshotId, ProjectionType.NOTE_SOURCE,
                result.embeddingVersion(), SourceRetrievalProjectionService.NOTE_SCHEMA_VERSION, result.noteIndex());
        if (expectedChunks <= 0 || qaReady != expectedChunks || noteReady != 1) {
            throw new IllegalStateException("Retrieval projection READY gate is incomplete");
        }

        jdbcTemplate.update("""
                update source_chunk
                set projection_status = 'PROJECTED', projected_at = current_timestamp
                where workspace_id = ? and source_id = ? and source_snapshot_id = ?
                """, workspaceId, sourceId, snapshotId);
        jdbcTemplate.update("""
                update source_snapshot
                set index_status = 'INDEXED', processing_stage = 'READY', next_index_retry_at = null
                where id = ? and source_id = ? and parse_status = 'PARSED'
                """, snapshotId, sourceId);
        int sourceReady = jdbcTemplate.update("""
                update source
                set index_status = 'INDEXED', status = 'READY',
                    updated_by = 'SYSTEM:RETRIEVAL_PROJECTION', updated_at = current_timestamp
                where id = ? and workspace_id = ?
                  and status = 'PROCESSING' and index_status = 'INDEXING'
                """, sourceId, workspaceId);
        if (sourceReady != 1) {
            throw new IllegalStateException("Source cannot be finalized as retrieval-ready");
        }
        List<StaleSnapshot> staleSnapshots = staleSnapshots(workspaceId, sourceId, snapshotId);
        for (StaleSnapshot stale : staleSnapshots) {
            projectionRepository.markSnapshotStale(stale.sourceSnapshotId());
        }
        sourceCatalogVersionService.bump(workspaceId);
        if (taskId != null && !taskId.isBlank()) {
            taskService.completeTask(taskId, "INDEXED",
                    "资料的 QA 与 Note 检索投影均已完成", sourceId);
        }
        return staleSnapshots;
    }

    /** 检索索引失败后自动重试的最大次数。 */
    public static final int MAX_AUTO_RETRIES = 3;
    private static final java.util.Set<String> NON_RETRYABLE_ERRORS = java.util.Set.of(
            "EMBEDDING_PROVIDER_DISABLED", "EMBEDDING_DIMENSION_MISMATCH");

    @Transactional
    public void finalizeFailed(
            String workspaceId,
            String sourceId,
            String snapshotId,
            String taskId,
            String errorCode
    ) {
        String normalizedErrorCode = normalizeErrorCode(errorCode);
        CurrentSnapshot current = lockCurrentSnapshot(workspaceId, sourceId, snapshotId);
        TaskService.TaskRef task = lockMatchingTask(taskId, workspaceId, sourceId);
        if (task != null && terminal(task.taskStatus())) {
            return;
        }
        if (current.current() && current.ready()) {
            return;
        }
        if (current.current() && !current.acceptsFailedTransition() && !current.failed()) {
            throw new IllegalStateException("Source is not awaiting retrieval projection failure finalization");
        }
        // 可重试的失败按 1、2、4 分钟退避自动重试，最多 3 次；配置类错误重试也不会成功，直接停下
        Integer attempts = jdbcTemplate.query(
                "select index_attempt_count from source_snapshot where id = ? and source_id = ?",
                rs -> rs.next() ? rs.getInt(1) : null, snapshotId, sourceId);
        boolean autoRetry = attempts != null && attempts < MAX_AUTO_RETRIES
                && !NON_RETRYABLE_ERRORS.contains(normalizedErrorCode);
        java.sql.Timestamp nextRetryAt = autoRetry
                ? java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(60L << attempts)) : null;
        jdbcTemplate.update("""
                update source_snapshot
                set index_status = 'FAILED', next_index_retry_at = ?
                where id = ? and source_id = ?
                  and parse_status = 'PARSED' and index_status = 'INDEXING'
                """, nextRetryAt, snapshotId, sourceId);
        jdbcTemplate.update("""
                update source_chunk
                set projection_status = 'FAILED', projected_at = null
                where workspace_id = ? and source_id = ? and source_snapshot_id = ?
                  and projection_status <> 'PROJECTED'
                """, workspaceId, sourceId, snapshotId);
        int sourceFailed = jdbcTemplate.update("""
                update source
                set index_status = 'FAILED', status = 'FAILED',
                    updated_by = 'SYSTEM:RETRIEVAL_PROJECTION', updated_at = current_timestamp
                where id = ? and workspace_id = ?
                  and status = 'PROCESSING' and index_status = 'INDEXING'
                  and exists (
                      select 1
                      from source_snapshot ss
                      where ss.id = ? and ss.source_id = ?
                        and ss.version_no = (
                            select max(current_ss.version_no)
                            from source_snapshot current_ss
                            where current_ss.source_id = ?
                        )
                  )
                """, sourceId, workspaceId, snapshotId, sourceId, sourceId);
        if (sourceFailed == 1) {
            sourceCatalogVersionService.bump(workspaceId);
        }
        if (task != null) {
            taskService.failTask(
                    taskId,
                    "INDEX_FAILED",
                    autoRetry
                            ? "资料解析已完成，但检索索引生成失败，将在 " + (1L << attempts) + " 分钟后自动重试"
                            : "资料解析已完成，但检索索引生成失败",
                    normalizedErrorCode,
                    autoRetry
            );
        }
    }

    private TaskService.TaskRef lockMatchingTask(
            String taskId,
            String workspaceId,
            String sourceId
    ) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        TaskService.TaskRef task = taskService.lockTaskRef(taskId);
        if (!workspaceId.equals(task.workspaceId())
                || !"SOURCE_PARSE".equals(task.taskType())
                || !"SOURCE".equals(task.targetType())
                || !sourceId.equals(task.targetId())) {
            throw new BusinessException(
                    "RETRIEVAL_PROJECTION_TASK_IDENTITY_MISMATCH",
                    "Retrieval projection failure task identity does not match the source"
            );
        }
        return task;
    }

    private boolean terminal(String taskStatus) {
        return "COMPLETED".equals(taskStatus)
                || "FAILED".equals(taskStatus)
                || "CANCELLED".equals(taskStatus);
    }

    private String normalizeErrorCode(String errorCode) {
        String normalized = errorCode == null ? "" : errorCode.trim().toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z0-9_]{1,80}")
                ? normalized
                : "RETRIEVAL_PROJECTION_FAILED";
    }

    private CurrentSnapshot lockCurrentSnapshot(String workspaceId, String sourceId, String snapshotId) {
        return jdbcTemplate.queryForObject("""
                select ss.version_no = max_ss.max_version as is_current,
                       s.status, s.index_status
                from source s
                join source_snapshot ss on ss.id = ? and ss.source_id = s.id
                join (select source_id, max(version_no) max_version from source_snapshot group by source_id) max_ss
                  on max_ss.source_id = s.id
                where s.workspace_id = ? and s.id = ? and s.status <> 'DELETED'
                for update
                """, (rs, rowNum) -> new CurrentSnapshot(
                        rs.getBoolean("is_current"),
                        rs.getString("status"),
                        rs.getString("index_status")),
                snapshotId, workspaceId, sourceId);
    }

    private int countChunks(String workspaceId, String sourceId, String snapshotId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from source_chunk
                where workspace_id = ? and source_id = ? and source_snapshot_id = ?
                """, Integer.class, workspaceId, sourceId, snapshotId);
        return count == null ? 0 : count;
    }

    private int countReady(
            String workspaceId, String sourceId, String snapshotId, ProjectionType type,
            String embeddingVersion, String schemaVersion, String targetIndex
    ) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(distinct entity_id) from retrieval_projection
                where workspace_id = ? and source_id = ? and source_snapshot_id = ?
                  and projection_type = ? and embedding_version = ?
                  and index_schema_version = ? and target_index = ? and status = 'READY'
                """, Integer.class, workspaceId, sourceId, snapshotId, type.name(),
                embeddingVersion, schemaVersion, targetIndex);
        return count == null ? 0 : count;
    }

    private record CurrentSnapshot(boolean current, String sourceStatus, String indexStatus) {
        private boolean ready() {
            return "READY".equals(sourceStatus) && "INDEXED".equals(indexStatus);
        }

        private boolean acceptsReadyTransition() {
            return "PROCESSING".equals(sourceStatus) && "INDEXING".equals(indexStatus);
        }

        private boolean acceptsFailedTransition() {
            return "PROCESSING".equals(sourceStatus) && "INDEXING".equals(indexStatus);
        }

        private boolean failed() {
            return "FAILED".equals(sourceStatus) && "FAILED".equals(indexStatus);
        }
    }

    public record StaleSnapshot(String sourceSnapshotId, ProjectionType projectionType, String targetIndex) {
    }
}
