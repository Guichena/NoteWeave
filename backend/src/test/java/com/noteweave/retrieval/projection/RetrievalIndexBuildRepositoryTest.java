package com.noteweave.retrieval.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.retrieval.projection.RetrievalIndexBuildRepository.BuildStatus;
import com.noteweave.retrieval.projection.RetrievalIndexBuildRepository.CreateIndexBuild;
import com.noteweave.retrieval.projection.RetrievalIndexBuildRepository.IndexBuild;
import com.noteweave.retrieval.projection.RetrievalProjectionRepository.ProjectionType;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class RetrievalIndexBuildRepositoryTest {
    private RetrievalIndexBuildRepository repository;
    private JdbcTemplate jdbcTemplate;
    private String workspaceId;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:retrieval-index-build-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new RetrievalIndexBuildRepository(jdbcTemplate);
        workspaceId = seedWorkspace();
    }

    @Test
    void completesOnlyAfterVerifiedCountsAndAliasSwitch() {
        IndexBuild created = repository.create(command(2));
        assertThat(created.status()).isEqualTo(BuildStatus.CREATED);

        repository.startBackfill(created.id());
        repository.updateCounts(created.id(), 2, 0);
        repository.startVerification(created.id());
        repository.startAliasSwitch(created.id());
        repository.complete(created.id());

        IndexBuild completed = repository.findById(created.id());
        assertThat(completed.status()).isEqualTo(BuildStatus.COMPLETED);
        assertThat(completed.startedAt()).isNotNull();
        assertThat(completed.aliasSwitchedAt()).isNotNull();
        assertThat(completed.completedAt()).isNotNull();
        assertThat(repository.findActive(workspaceId)).isEmpty();
    }

    @Test
    void refusesAliasSwitchWhenBackfillIsIncomplete() {
        IndexBuild created = repository.create(command(3));
        repository.startBackfill(created.id());
        repository.updateCounts(created.id(), 2, 0);
        repository.startVerification(created.id());

        assertThatThrownBy(() -> repository.startAliasSwitch(created.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot transition");
    }

    @Test
    void recordsTerminalFailureWithStableReasonCode() {
        IndexBuild created = repository.create(command(4));
        repository.startBackfill(created.id());
        repository.updateCounts(created.id(), 2, 1);
        repository.fail(created.id(), "RETRIEVAL_INDEX_BACKFILL_FAILED");

        IndexBuild failed = repository.findById(created.id());
        assertThat(failed.status()).isEqualTo(BuildStatus.FAILED);
        assertThat(failed.lastErrorCode()).isEqualTo("RETRIEVAL_INDEX_BACKFILL_FAILED");
        assertThat(failed.completedAt()).isNotNull();
    }

    private CreateIndexBuild command(long expectedCount) {
        return new CreateIndexBuild(
                workspaceId,
                ProjectionType.QA_CHUNK,
                "noteweave_qa_chunk_previous",
                "noteweave_qa_chunk_v1_" + workspaceId,
                "openai-compatible",
                "embedding-v1",
                1024,
                "embedding-v1:1024",
                "qa-chunk-index-v1",
                expectedCount);
    }

    private String seedWorkspace() {
        String userId = UUID.randomUUID().toString();
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                insert into users(id, username, email, display_name, status)
                values (?, ?, ?, 'Index Builder', 'ACTIVE')
                """, userId, "index-builder-" + userId, userId + "@example.com");
        jdbcTemplate.update("""
                insert into workspace(id, owner_id, name, description, status)
                values (?, ?, 'Index Build Workspace', '', 'ACTIVE')
                """, id, userId);
        return id;
    }
}
