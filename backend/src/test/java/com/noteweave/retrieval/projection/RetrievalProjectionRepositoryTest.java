package com.noteweave.retrieval.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.retrieval.projection.RetrievalProjectionRepository.CreateProjection;
import com.noteweave.retrieval.projection.RetrievalProjectionRepository.Projection;
import com.noteweave.retrieval.projection.RetrievalProjectionRepository.ProjectionStatus;
import com.noteweave.retrieval.projection.RetrievalProjectionRepository.ProjectionType;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class RetrievalProjectionRepositoryTest {
    private JdbcTemplate jdbcTemplate;
    private RetrievalProjectionRepository repository;
    private String workspaceId;
    private String sourceId;
    private String snapshotId;
    private String chunkId;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:retrieval-projection-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new RetrievalProjectionRepository(jdbcTemplate);
        workspaceId = UUID.randomUUID().toString();
        sourceId = UUID.randomUUID().toString();
        snapshotId = UUID.randomUUID().toString();
        chunkId = UUID.randomUUID().toString();
        seedSourceGraph();
    }

    @Test
    void persistsAndAdvancesProjectionStateMachine() {
        Projection pending = repository.createPending(command(ProjectionType.QA_CHUNK, chunkId));

        assertThat(pending.status()).isEqualTo(ProjectionStatus.PENDING);
        assertThat(pending.embeddingModel()).isEqualTo("embedding-v1");
        assertThat(pending.embeddingDimensions()).isEqualTo(3);

        repository.markEmbedding(pending.id());
        repository.markIndexing(pending.id());
        repository.markReady(pending.id());

        Projection ready = repository.findById(pending.id());
        assertThat(ready.status()).isEqualTo(ProjectionStatus.READY);
        assertThat(ready.projectedAt()).isNotNull();
        assertThat(repository.findReadyBySnapshot(snapshotId, ProjectionType.QA_CHUNK))
                .extracting(Projection::entityId)
                .containsExactly(chunkId);
    }

    @Test
    void rejectsIllegalTransitionsAndCanInvalidateCurrentProjection() {
        Projection pending = repository.createPending(command(ProjectionType.NOTE_SOURCE, sourceId));

        assertThatThrownBy(() -> repository.markReady(pending.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot transition");

        repository.markEmbedding(pending.id());
        repository.markIndexing(pending.id());
        repository.markReady(pending.id());
        assertThat(repository.markSnapshotStale(snapshotId)).isEqualTo(1);
        assertThat(repository.findById(pending.id()).status()).isEqualTo(ProjectionStatus.STALE);
    }

    @Test
    void recordsStableFailureStateAndRetryTime() {
        Projection pending = repository.createPending(command(ProjectionType.QA_CHUNK, chunkId));
        Instant retryAt = Instant.now().plusSeconds(60);

        repository.markFailed(pending.id(), "EMBEDDING_PROVIDER_REQUEST_FAILED", retryAt);

        Projection failed = repository.findById(pending.id());
        assertThat(failed.status()).isEqualTo(ProjectionStatus.FAILED);
        assertThat(failed.attemptCount()).isEqualTo(1);
        assertThat(failed.lastErrorCode()).isEqualTo("EMBEDDING_PROVIDER_REQUEST_FAILED");
        assertThat(failed.nextRetryAt()).isNotNull();
    }

    @Test
    void sameEmbeddingAndSchemaCanProjectIntoANewPhysicalGeneration() {
        Projection first = repository.createPending(command(ProjectionType.QA_CHUNK, chunkId));
        CreateProjection generationTwo = new CreateProjection(
                workspaceId, ProjectionType.QA_CHUNK, chunkId, sourceId, snapshotId,
                "sha256:content", "sha256:embedding", "openai-compatible", "embedding-v1",
                3, "embedding-v1:3", "retrieval-index-v1", "noteweave-test-index-build-2");

        Projection second = repository.createPending(generationTwo);

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.targetIndex()).isEqualTo("noteweave-test-index-build-2");
    }

    private CreateProjection command(ProjectionType type, String entityId) {
        return new CreateProjection(
                workspaceId,
                type,
                entityId,
                sourceId,
                snapshotId,
                "sha256:content",
                "sha256:embedding",
                "openai-compatible",
                "embedding-v1",
                3,
                "embedding-v1:3",
                "retrieval-index-v1",
                "noteweave-test-index");
    }

    private void seedSourceGraph() {
        String userId = UUID.randomUUID().toString();
        String fileObjectId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                insert into users(id, username, email, display_name, status)
                values (?, ?, ?, ?, 'ACTIVE')
                """, userId, "retrieval-" + userId, userId + "@example.com", "Retrieval Test");
        jdbcTemplate.update("""
                insert into workspace(id, owner_id, name, description, status)
                values (?, ?, ?, '', 'ACTIVE')
                """, workspaceId, userId, "Retrieval Workspace");
        jdbcTemplate.update("""
                insert into file_object(id, workspace_id, object_key, sha256, file_size, mime_type)
                values (?, ?, ?, ?, 1, 'text/plain')
                """, fileObjectId, workspaceId, "retrieval/source.txt", "a".repeat(64));
        jdbcTemplate.update("""
                insert into source(
                    id, workspace_id, file_object_id, title, source_type,
                    status, parse_status, index_status
                ) values (?, ?, ?, 'retrieval-source', 'USER_UPLOAD', 'PROCESSING', 'PARSED', 'INDEXING')
                """, sourceId, workspaceId, fileObjectId);
        jdbcTemplate.update("""
                insert into source_snapshot(
                    id, source_id, file_object_id, version_no, object_key, sha256,
                    parse_status, index_status
                ) values (?, ?, ?, 1, 'retrieval/snapshot', ?, 'PARSED', 'INDEXING')
                """, snapshotId, sourceId, fileObjectId, "b".repeat(64));
        jdbcTemplate.update("""
                insert into source_chunk(
                    id, workspace_id, source_id, source_snapshot_id, chunk_no,
                    content, token_estimate, projection_status
                ) values (?, ?, ?, ?, 0, 'retrieval content', 2, 'PENDING')
                """, chunkId, workspaceId, sourceId, snapshotId);
    }
}
