package com.noteweave.retrieval.projection;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RetrievalProjectionRepository {
    private final JdbcTemplate jdbcTemplate;

    public RetrievalProjectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Projection createPending(CreateProjection command) {
        List<Projection> existing = findVersion(command);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                insert into retrieval_projection(
                    id, workspace_id, projection_type, entity_id, source_id, source_snapshot_id,
                    content_hash, embedding_text_hash, embedding_provider, embedding_model,
                    embedding_dimensions, embedding_version, index_schema_version, target_index, status
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                """,
                id,
                command.workspaceId(),
                command.projectionType().name(),
                command.entityId(),
                command.sourceId(),
                command.sourceSnapshotId(),
                command.contentHash(),
                command.embeddingTextHash(),
                command.embeddingProvider(),
                command.embeddingModel(),
                command.embeddingDimensions(),
                command.embeddingVersion(),
                command.indexSchemaVersion(),
                command.targetIndex());
        return findById(id);
    }

    private List<Projection> findVersion(CreateProjection command) {
        return jdbcTemplate.query(BASE_SELECT + """
                where projection_type = ? and entity_id = ? and source_snapshot_id = ?
                  and embedding_version = ? and index_schema_version = ? and target_index = ?
                """, this::map,
                command.projectionType().name(), command.entityId(), command.sourceSnapshotId(),
                command.embeddingVersion(), command.indexSchemaVersion(), command.targetIndex());
    }

    public Projection findById(String id) {
        return jdbcTemplate.queryForObject(BASE_SELECT + " where id = ?", this::map, id);
    }

    private Projection map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Projection(
                rs.getString("id"),
                rs.getString("workspace_id"),
                ProjectionType.valueOf(rs.getString("projection_type")),
                rs.getString("entity_id"),
                rs.getString("source_id"),
                rs.getString("source_snapshot_id"),
                rs.getString("content_hash"),
                rs.getString("embedding_text_hash"),
                rs.getString("embedding_provider"),
                rs.getString("embedding_model"),
                rs.getInt("embedding_dimensions"),
                rs.getString("embedding_version"),
                rs.getString("index_schema_version"),
                rs.getString("target_index"),
                ProjectionStatus.valueOf(rs.getString("status")),
                rs.getInt("attempt_count"),
                rs.getString("last_error_code"),
                instant(rs.getTimestamp("next_retry_at")),
                instant(rs.getTimestamp("projected_at")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    public List<Projection> findReadyBySnapshot(String sourceSnapshotId, ProjectionType type) {
        return jdbcTemplate.query(BASE_SELECT + """
                where source_snapshot_id = ? and projection_type = ? and status = 'READY'
                order by entity_id
                """, this::map, sourceSnapshotId, type.name());
    }

    public void markEmbedding(String id) {
        transition(id, ProjectionStatus.PENDING, ProjectionStatus.EMBEDDING, null, null, false);
    }

    public Projection prepareForProjection(Projection projection) {
        if (projection.status() == ProjectionStatus.READY || projection.status() == ProjectionStatus.PENDING) {
            return projection;
        }
        int updated = jdbcTemplate.update("""
                update retrieval_projection
                set status = 'PENDING', last_error_code = null, next_retry_at = null,
                    updated_at = current_timestamp
                where id = ? and status in ('FAILED', 'EMBEDDING', 'INDEXING')
                """, projection.id());
        if (updated != 1) {
            return findById(projection.id());
        }
        return findById(projection.id());
    }

    public void markIndexing(String id) {
        transition(id, ProjectionStatus.EMBEDDING, ProjectionStatus.INDEXING, null, null, false);
    }

    public void markReady(String id) {
        int updated = jdbcTemplate.update("""
                update retrieval_projection
                set status = 'READY', last_error_code = null, next_retry_at = null,
                    projected_at = current_timestamp, updated_at = current_timestamp
                where id = ? and status = 'INDEXING'
                """, id);
        requireTransition(updated, id, ProjectionStatus.INDEXING, ProjectionStatus.READY);
    }

    public void markFailed(String id, String errorCode, Instant nextRetryAt) {
        int updated = jdbcTemplate.update("""
                update retrieval_projection
                set status = 'FAILED', attempt_count = attempt_count + 1,
                    last_error_code = ?, next_retry_at = ?, updated_at = current_timestamp
                where id = ? and status in ('PENDING', 'EMBEDDING', 'INDEXING')
                """, errorCode, nextRetryAt == null ? null : java.sql.Timestamp.from(nextRetryAt), id);
        if (updated != 1) {
            throw new IllegalStateException("Projection " + id + " cannot transition to FAILED");
        }
    }

    public int markSnapshotStale(String sourceSnapshotId) {
        return jdbcTemplate.update("""
                update retrieval_projection
                set status = 'STALE', updated_at = current_timestamp
                where source_snapshot_id = ? and status = 'READY'
                """, sourceSnapshotId);
    }

    private void transition(
            String id,
            ProjectionStatus expected,
            ProjectionStatus next,
            String errorCode,
            Instant nextRetryAt,
            boolean incrementAttempt
    ) {
        int updated = jdbcTemplate.update("""
                update retrieval_projection
                set status = ?, last_error_code = ?, next_retry_at = ?,
                    attempt_count = attempt_count + ?, updated_at = current_timestamp
                where id = ? and status = ?
                """, next.name(), errorCode,
                nextRetryAt == null ? null : java.sql.Timestamp.from(nextRetryAt),
                incrementAttempt ? 1 : 0, id, expected.name());
        requireTransition(updated, id, expected, next);
    }

    private void requireTransition(int updated, String id, ProjectionStatus expected, ProjectionStatus next) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "Projection " + id + " cannot transition from " + expected + " to " + next);
        }
    }

    private Instant instant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static final String BASE_SELECT = """
            select id, workspace_id, projection_type, entity_id, source_id, source_snapshot_id,
                   content_hash, embedding_text_hash, embedding_provider, embedding_model,
                   embedding_dimensions, embedding_version, index_schema_version, target_index,
                   status, attempt_count, last_error_code, next_retry_at, projected_at,
                   created_at, updated_at
            from retrieval_projection
            """;

    public enum ProjectionType {
        QA_CHUNK,
        NOTE_SOURCE
    }

    public enum ProjectionStatus {
        PENDING,
        EMBEDDING,
        INDEXING,
        READY,
        FAILED,
        STALE
    }

    public record CreateProjection(
            String workspaceId,
            ProjectionType projectionType,
            String entityId,
            String sourceId,
            String sourceSnapshotId,
            String contentHash,
            String embeddingTextHash,
            String embeddingProvider,
            String embeddingModel,
            int embeddingDimensions,
            String embeddingVersion,
            String indexSchemaVersion,
            String targetIndex
    ) {
    }

    public record Projection(
            String id,
            String workspaceId,
            ProjectionType projectionType,
            String entityId,
            String sourceId,
            String sourceSnapshotId,
            String contentHash,
            String embeddingTextHash,
            String embeddingProvider,
            String embeddingModel,
            int embeddingDimensions,
            String embeddingVersion,
            String indexSchemaVersion,
            String targetIndex,
            ProjectionStatus status,
            int attemptCount,
            String lastErrorCode,
            Instant nextRetryAt,
            Instant projectedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
