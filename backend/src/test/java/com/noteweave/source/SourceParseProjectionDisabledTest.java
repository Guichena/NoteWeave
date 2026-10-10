package com.noteweave.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.security.AuditActorProvider;
import com.noteweave.storage.ObjectStorage;
import com.noteweave.task.TaskCommandPort;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SourceParseProjectionDisabledTest {

    @Test
    void kafkaEnabledAndElasticsearchDisabledShouldExposeLocalOnlyParsedState() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:source-parse-no-projection-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        createSchema(jdbcTemplate);
        jdbcTemplate.update("""
                insert into file_object(id, mime_type) values ('file-1', 'text/markdown')
                """);
        jdbcTemplate.update("""
                insert into source(id, workspace_id, file_object_id, title, source_type, status, parse_status, index_status)
                values ('source-1', 'workspace-1', 'file-1', 'Kafka without Elasticsearch', 'TEXT',
                        'PROCESSING', 'PENDING', 'PENDING')
                """);
        jdbcTemplate.update("""
                insert into source_snapshot(id, source_id, parse_status, index_status)
                values ('snapshot-1', 'source-1', 'PENDING', 'PENDING')
                """);

        DocumentChunker documentChunker = mock(DocumentChunker.class);
        when(documentChunker.chunk("parsed content")).thenReturn(List.of("parsed content"));
        SourceMessagingMode messagingMode = mock(SourceMessagingMode.class);
        when(messagingMode.isAsyncEnabled()).thenReturn(true);
        AuditActorProvider actorProvider = mock(AuditActorProvider.class);
        when(actorProvider.currentOrSystem("SOURCE_PARSE")).thenReturn("system:test");
        TaskCommandPort taskCommandPort = mock(TaskCommandPort.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

        SourceParseService service = new SourceParseService(
                jdbcTemplate,
                documentChunker,
                new SourceDocumentTextExtractor(),
                new ObjectMapper(),
                mock(ObjectStorage.class),
                messagingMode,
                new NoteWeaveProperties(
                        null, null, null,
                        new NoteWeaveProperties.Kafka(true, null),
                        new NoteWeaveProperties.Elasticsearch(false, "localhost", 9200, "http", "noteweave_chunk"),
                        null
                ),
                taskCommandPort,
                new DataSourceTransactionManager(dataSource),
                actorProvider,
                mock(SourceCatalogVersionService.class),
                eventPublisher
        );

        service.parseAndIndex("workspace-1", "source-1", "snapshot-1", "parsed content".getBytes());

        assertThat(jdbcTemplate.queryForMap(
                "select status, parse_status, index_status from source where id = 'source-1'"))
                .containsEntry("STATUS", "READY")
                .containsEntry("PARSE_STATUS", "PARSED")
                .containsEntry("INDEX_STATUS", "DISABLED");
        assertThat(jdbcTemplate.queryForMap(
                "select parse_status, index_status from source_snapshot where id = 'snapshot-1'"))
                .containsEntry("PARSE_STATUS", "PARSED")
                .containsEntry("INDEX_STATUS", "DISABLED");
        assertThat(jdbcTemplate.queryForObject(
                "select projection_status from source_chunk where source_snapshot_id = 'snapshot-1'",
                String.class
        )).isEqualTo("NOT_PROJECTED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from task_outbox", Integer.class)).isZero();
        verifyNoInteractions(taskCommandPort, eventPublisher);
    }

    private void createSchema(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
                create table file_object (
                    id varchar(64) primary key,
                    mime_type varchar(160) not null
                )
                """);
        jdbcTemplate.execute("""
                create table source (
                    id varchar(64) primary key,
                    workspace_id varchar(64) not null,
                    file_object_id varchar(64),
                    title varchar(300) not null,
                    source_type varchar(64) not null,
                    status varchar(32) not null,
                    parse_status varchar(32) not null,
                    index_status varchar(32) not null,
                    summary varchar(1000),
                    tags_json clob,
                    metadata_json clob,
                    updated_by varchar(120),
                    updated_at timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table source_snapshot (
                    id varchar(64) primary key,
                    source_id varchar(64) not null,
                    parse_status varchar(32) not null,
                    index_status varchar(32) not null,
                    processing_stage varchar(32), index_attempt_count int default 0 not null,
                    next_index_retry_at timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table source_chunk (
                    id varchar(64) primary key,
                    workspace_id varchar(64) not null,
                    source_id varchar(64) not null,
                    source_snapshot_id varchar(64) not null,
                    chunk_no int not null,
                    heading varchar(300),
                    content clob,
                    token_estimate int,
                    location_info varchar(500),
                    projection_status varchar(32) default 'PENDING',
                    projected_at timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table source_window (
                    id varchar(64) primary key,
                    source_chunk_id varchar(64) not null,
                    window_no int not null,
                    content clob,
                    location_info varchar(500)
                )
                """);
        jdbcTemplate.execute("""
                create table task (
                    id varchar(64) primary key,
                    task_type varchar(64) not null,
                    target_id varchar(64),
                    created_at timestamp default current_timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table task_outbox (
                    id varchar(64) primary key,
                    task_id varchar(64),
                    topic varchar(160),
                    message_key varchar(160),
                    payload_json clob,
                    status varchar(32)
                )
                """);
    }
}
