package com.noteweave.retrieval.projection;

import com.noteweave.retrieval.projection.RetrievalProjectionRepository.ProjectionType;
import com.noteweave.source.SourceCatalogVersionService;
import com.noteweave.task.TaskService;
import java.util.List;
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
    public void finalizeReady(
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
                update source_snapshot set index_status = 'INDEXED'
                where id = ? and source_id = ? and parse_status = 'PARSED'
                """, snapshotId, sourceId);
        int sourceReady = jdbcTemplate.update("""
                update source
                set index_status = 'INDEXED', status = 'READY',
                    updated_by = 'SYSTEM:RETRIEVAL_PROJECTION', updated_at = current_timestamp
                where id = ? and workspace_id = ? and status <> 'DELETED'
                """, sourceId, workspaceId);
        if (sourceReady != 1) {
            throw new IllegalStateException("Source cannot be finalized as retrieval-ready");
        }
        for (StaleSnapshot stale : staleSnapshots(workspaceId, sourceId, snapshotId)) {
            projectionRepository.markSnapshotStale(stale.sourceSnapshotId());
        }
        sourceCatalogVersionService.bump(workspaceId);
        if (taskId != null && !taskId.isBlank()) {
            taskService.completeTask(taskId, "INDEXED",
                    "资料的 QA 与 Note 检索投影均已完成", sourceId);
        }
    }

    private CurrentSnapshot lockCurrentSnapshot(String workspaceId, String sourceId, String snapshotId) {
        return jdbcTemplate.queryForObject("""
                select ss.version_no = max_ss.max_version as is_current
                from source s
                join source_snapshot ss on ss.id = ? and ss.source_id = s.id
                join (select source_id, max(version_no) max_version from source_snapshot group by source_id) max_ss
                  on max_ss.source_id = s.id
                where s.workspace_id = ? and s.id = ? and s.status <> 'DELETED'
                for update
                """, (rs, rowNum) -> new CurrentSnapshot(rs.getBoolean("is_current")),
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

    private record CurrentSnapshot(boolean current) {
    }

    public record StaleSnapshot(String sourceSnapshotId, ProjectionType projectionType, String targetIndex) {
    }
}
