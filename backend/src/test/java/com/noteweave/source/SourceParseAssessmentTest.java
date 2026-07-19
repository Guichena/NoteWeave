package com.noteweave.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.security.AuditActorProvider;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.storage.ObjectStorage;
import com.noteweave.task.TaskCommandPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.context.ApplicationEventPublisher;

class SourceParseAssessmentTest {

    private JdbcTemplate jdbcTemplate;
    private SourceParseService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:source-parse-assessment-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table source (
                    id varchar(64) primary key,
                    workspace_id varchar(64) not null,
                    status varchar(32) not null
                )
                """);
        jdbcTemplate.execute("""
                create table source_snapshot (
                    id varchar(64) primary key,
                    source_id varchar(64) not null,
                    parse_status varchar(32) not null
                )
                """);
        service = new SourceParseService(
                jdbcTemplate,
                mock(DocumentChunker.class),
                new ObjectMapper(),
                mock(ObjectStorage.class),
                mock(SourceMessagingMode.class),
                new NoteWeaveProperties(
                        null, null, null, null,
                        new NoteWeaveProperties.Elasticsearch(false, "localhost", 9200, "http", "noteweave_chunk"),
                        null
                ),
                mock(TaskCommandPort.class),
                new DataSourceTransactionManager(dataSource),
                mock(AuditActorProvider.class),
                mock(SourceCatalogVersionService.class),
                mock(ApplicationEventPublisher.class)
        );
    }

    @Test
    void pendingSnapshotShouldBeProcessable() {
        insertSource("PROCESSING", "PENDING");

        assertThat(assessment().disposition())
                .isEqualTo(SourceParseService.SourceParseDisposition.PROCESSABLE);
    }

    @Test
    void deletedSourceShouldBeCancelled() {
        insertSource("DELETED", "DELETED");

        assertThat(assessment().disposition())
                .isEqualTo(SourceParseService.SourceParseDisposition.TARGET_DELETED);
    }

    @Test
    void parsedSnapshotShouldBeTreatedAsIdempotentDuplicate() {
        insertSource("READY", "PARSED");

        assertThat(assessment().disposition())
                .isEqualTo(SourceParseService.SourceParseDisposition.ALREADY_HANDLED);
    }

    @Test
    void missingSourceOrSnapshotShouldFailExplicitly() {
        assertThat(assessment().disposition())
                .isEqualTo(SourceParseService.SourceParseDisposition.TARGET_MISSING);

        jdbcTemplate.update(
                "insert into source(id, workspace_id, status) values ('source-1', 'workspace-1', 'PROCESSING')");
        assertThat(assessment().disposition())
                .isEqualTo(SourceParseService.SourceParseDisposition.TARGET_MISSING);
    }

    private void insertSource(String sourceStatus, String snapshotStatus) {
        jdbcTemplate.update(
                "insert into source(id, workspace_id, status) values ('source-1', 'workspace-1', ?)",
                sourceStatus
        );
        jdbcTemplate.update(
                "insert into source_snapshot(id, source_id, parse_status) values ('snapshot-1', 'source-1', ?)",
                snapshotStatus
        );
    }

    private SourceParseService.SourceParseAssessment assessment() {
        return service.assessProcessability("workspace-1", "source-1", "snapshot-1");
    }
}
