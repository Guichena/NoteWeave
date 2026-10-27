package com.noteweave.retrieval.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.noteweave.knowledge.WikiIngestService;
import com.noteweave.knowledge.WikiSourceReadyListener;
import com.noteweave.source.SourceCatalogVersionService;
import com.noteweave.source.SourceRetrievalReadyEvent;
import com.noteweave.task.TaskService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 资料索引完成后才触发 Wiki 编译：上传完成时资料仍在解析，提前编译会因未就绪而失败。 */
class SourceRetrievalReadyEventTest {

    private static final String EMBEDDING = "embedding-v1";

    @Test
    void finalizingReadyPublishesOneEventAndRepeatedFinalizationDoesNot() {
        JdbcTemplate jdbc = database();
        List<Object> events = new ArrayList<>();
        SourceRetrievalProjectionFinalizer finalizer = new SourceRetrievalProjectionFinalizer(
                jdbc, mock(RetrievalProjectionRepository.class), mock(SourceCatalogVersionService.class),
                mock(TaskService.class), events::add);
        SourceRetrievalProjectionService.ProjectionResult result =
                new SourceRetrievalProjectionService.ProjectionResult(1, 1, true, "qa-index", "note-index", EMBEDDING);

        finalizer.finalizeReady("workspace-1", "source-1", "snapshot-1", null, result);
        finalizer.finalizeReady("workspace-1", "source-1", "snapshot-1", null, result);

        assertThat(events).containsExactly(new SourceRetrievalReadyEvent("workspace-1", "source-1"));
        assertThat(jdbc.queryForObject("select status from source where id = 'source-1'", String.class))
                .isEqualTo("READY");
    }

    @Test
    void wikiListenerKeepsDirectEnqueueWithoutTheIntentChannel() {
        WikiIngestService wikiIngestService = mock(WikiIngestService.class);

        new WikiSourceReadyListener(wikiIngestService)
                .onSourceReady(new SourceRetrievalReadyEvent("workspace-1", "source-1"));

        verify(wikiIngestService).enqueueSourceIngestForReadySource("workspace-1", "source-1");
    }

    private JdbcTemplate database() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:source-ready-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("""
                create table source (
                    id varchar(64) primary key, workspace_id varchar(64) not null,
                    status varchar(32) not null, index_status varchar(32) not null,
                    updated_by varchar(120), updated_at timestamp default current_timestamp)
                """);
        jdbc.execute("""
                create table source_snapshot (
                    id varchar(64) primary key, source_id varchar(64) not null, version_no int not null,
                    parse_status varchar(32) not null, index_status varchar(32) not null,
                    processing_stage varchar(32), index_attempt_count int default 0 not null,
                    next_index_retry_at timestamp)
                """);
        jdbc.execute("""
                create table source_chunk (
                    id varchar(64) primary key, workspace_id varchar(64) not null, source_id varchar(64) not null,
                    source_snapshot_id varchar(64) not null, projection_status varchar(32) not null,
                    projected_at timestamp)
                """);
        jdbc.execute("""
                create table retrieval_projection (
                    id varchar(64) primary key, workspace_id varchar(64), source_id varchar(64),
                    source_snapshot_id varchar(64), projection_type varchar(32), entity_id varchar(64),
                    embedding_version varchar(64), index_schema_version varchar(64), target_index varchar(120),
                    status varchar(32))
                """);
        jdbc.update("insert into source(id, workspace_id, status, index_status) values ('source-1', 'workspace-1', 'PROCESSING', 'INDEXING')");
        jdbc.update("""
                insert into source_snapshot(id, source_id, version_no, parse_status, index_status)
                values ('snapshot-1', 'source-1', 1, 'PARSED', 'INDEXING')
                """);
        jdbc.update("""
                insert into source_chunk(id, workspace_id, source_id, source_snapshot_id, projection_status)
                values ('chunk-1', 'workspace-1', 'source-1', 'snapshot-1', 'PENDING')
                """);
        jdbc.update("""
                insert into retrieval_projection values
                ('p1', 'workspace-1', 'source-1', 'snapshot-1', 'QA_CHUNK', 'chunk-1', ?, ?, 'qa-index', 'READY'),
                ('p2', 'workspace-1', 'source-1', 'snapshot-1', 'NOTE_SOURCE', 'source-1', ?, ?, 'note-index', 'READY')
                """, EMBEDDING, SourceRetrievalProjectionService.QA_SCHEMA_VERSION,
                EMBEDDING, SourceRetrievalProjectionService.NOTE_SCHEMA_VERSION);
        return jdbc;
    }
}
