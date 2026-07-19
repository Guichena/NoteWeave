package com.noteweave.retrieval.projection;

import com.noteweave.retrieval.projection.RetrievalProjectionRepository.ProjectionType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RetrievalIndexBuildRepository {
    private final JdbcTemplate jdbcTemplate;

    public RetrievalIndexBuildRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public IndexBuild create(CreateIndexBuild command) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                insert into retrieval_index_build(
                    id, workspace_id, projection_type, from_index, target_index,
                    embedding_provider, embedding_model, embedding_dimensions,
                    embedding_version, index_schema_version, status, expected_count
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CREATED', ?)
                """,
                id,
                command.workspaceId(),
                command.projectionType().name(),
                blankToNull(command.fromIndex()),
                command.targetIndex(),
                command.embeddingProvider(),
                command.embeddingModel(),
                command.embeddingDimensions(),
                command.embeddingVersion(),
                command.indexSchemaVersion(),
                Math.max(0L, command.expectedCount()));
        return findById(id);
    }

    public IndexBuild findById(String id) {
        return jdbcTemplate.queryForObject(BASE_SELECT + " where id = ?", this::map, id);
    }

    public List<IndexBuild> findActive(String workspaceId) {
        return jdbcTemplate.query(BASE_SELECT + """
                where workspace_id = ? and status not in ('COMPLETED', 'FAILED')
                order by created_at, id
                """, this::map, workspaceId);
    }

    public List<IndexBuild> findByWorkspace(String workspaceId) {
        return jdbcTemplate.query(BASE_SELECT + """
                where workspace_id = ?
                order by created_at desc, id desc
                """, this::map, workspaceId);
    }

    public void startBackfill(String id) {
        transition(id, BuildStatus.CREATED, BuildStatus.BACKFILLING, true, false);
    }

    public void updateCounts(String id, long readyCount, long failedCount) {
        int updated = jdbcTemplate.update("""
                update retrieval_index_build
                set ready_count = ?, failed_count = ?, updated_at = current_timestamp
                where id = ? and status in ('BACKFILLING', 'VERIFYING')
                  and ? >= 0 and ? >= 0 and (? + ?) <= expected_count
                """,
                readyCount, failedCount, id,
                readyCount, failedCount, readyCount, failedCount);
        if (updated != 1) {
            throw new IllegalStateException("Index build " + id + " cannot accept these counts");
        }
    }

    public void startVerification(String id) {
        transition(id, BuildStatus.BACKFILLING, BuildStatus.VERIFYING, false, false);
    }

    public void startAliasSwitch(String id) {
        int updated = jdbcTemplate.update("""
                update retrieval_index_build
                set status = 'SWITCHING', updated_at = current_timestamp
                where id = ? and status = 'VERIFYING'
                  and ready_count = expected_count and failed_count = 0
                """, id);
        requireTransition(updated, id, BuildStatus.VERIFYING, BuildStatus.SWITCHING);
    }

    public void complete(String id) {
        int updated = jdbcTemplate.update("""
                update retrieval_index_build
                set status = 'COMPLETED', alias_switched_at = current_timestamp,
                    completed_at = current_timestamp, last_error_code = null,
                    updated_at = current_timestamp
                where id = ? and status = 'SWITCHING'
                """, id);
        requireTransition(updated, id, BuildStatus.SWITCHING, BuildStatus.COMPLETED);
    }

    public void fail(String id, String errorCode) {
        int updated = jdbcTemplate.update("""
                update retrieval_index_build
                set status = 'FAILED', last_error_code = ?, completed_at = current_timestamp,
                    updated_at = current_timestamp
                where id = ? and status not in ('COMPLETED', 'FAILED')
                """, errorCode, id);
        if (updated != 1) {
            throw new IllegalStateException("Index build " + id + " cannot transition to FAILED");
        }
    }

    private void transition(
            String id,
            BuildStatus expected,
            BuildStatus next,
            boolean setStartedAt,
            boolean setCompletedAt
    ) {
        int updated = jdbcTemplate.update("""
                update retrieval_index_build
                set status = ?,
                    started_at = case when ? then current_timestamp else started_at end,
                    completed_at = case when ? then current_timestamp else completed_at end,
                    updated_at = current_timestamp
                where id = ? and status = ?
                """, next.name(), setStartedAt, setCompletedAt, id, expected.name());
        requireTransition(updated, id, expected, next);
    }

    private void requireTransition(int updated, String id, BuildStatus expected, BuildStatus next) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "Index build " + id + " cannot transition from " + expected + " to " + next);
        }
    }

    private IndexBuild map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new IndexBuild(
                rs.getString("id"),
                rs.getString("workspace_id"),
                ProjectionType.valueOf(rs.getString("projection_type")),
                rs.getString("from_index"),
                rs.getString("target_index"),
                rs.getString("embedding_provider"),
                rs.getString("embedding_model"),
                rs.getInt("embedding_dimensions"),
                rs.getString("embedding_version"),
                rs.getString("index_schema_version"),
                BuildStatus.valueOf(rs.getString("status")),
                rs.getLong("expected_count"),
                rs.getLong("ready_count"),
                rs.getLong("failed_count"),
                rs.getString("last_error_code"),
                instant(rs.getTimestamp("alias_switched_at")),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("completed_at")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private Instant instant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static final String BASE_SELECT = """
            select id, workspace_id, projection_type, from_index, target_index,
                   embedding_provider, embedding_model, embedding_dimensions,
                   embedding_version, index_schema_version, status,
                   expected_count, ready_count, failed_count, last_error_code,
                   alias_switched_at, started_at, completed_at, created_at, updated_at
            from retrieval_index_build
            """;

    public enum BuildStatus {
        CREATED,
        BACKFILLING,
        VERIFYING,
        SWITCHING,
        COMPLETED,
        FAILED
    }

    public record CreateIndexBuild(
            String workspaceId,
            ProjectionType projectionType,
            String fromIndex,
            String targetIndex,
            String embeddingProvider,
            String embeddingModel,
            int embeddingDimensions,
            String embeddingVersion,
            String indexSchemaVersion,
            long expectedCount
    ) {
    }

    public record IndexBuild(
            String id,
            String workspaceId,
            ProjectionType projectionType,
            String fromIndex,
            String targetIndex,
            String embeddingProvider,
            String embeddingModel,
            int embeddingDimensions,
            String embeddingVersion,
            String indexSchemaVersion,
            BuildStatus status,
            long expectedCount,
            long readyCount,
            long failedCount,
            String lastErrorCode,
            Instant aliasSwitchedAt,
            Instant startedAt,
            Instant completedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
