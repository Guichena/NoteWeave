package com.noteweave.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import java.io.IOException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.noteweave.task.TaskService;
import com.noteweave.source.SourceCatalogVersionService;

class ElasticsearchIndexerTest {

    @Test
    void malformedProjectionEventShouldEscapeListenerForKafkaRetry() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ElasticsearchIndexer indexer = new ElasticsearchIndexer(
                mock(ElasticsearchClient.class),
                enabledProperties(),
                new ObjectMapper(),
                mock(org.springframework.jdbc.core.JdbcTemplate.class),
                mock(com.noteweave.task.TaskService.class),
                meterRegistry
        );
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "noteweave.source.index",
                0,
                3L,
                "chunk-1",
                """
                        {"workspaceId":"workspace-1","chunkId":"chunk-1"}
                        """
        );

        assertThatThrownBy(() -> indexer.onSourceIndexEvent(record))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ES source.index projection failed")
                .hasRootCauseMessage("source.index missing required field: sourceId");
        assertThat(meterRegistry.get("noteweave.elasticsearch.projection.failure").counter().count()).isEqualTo(1.0);
    }

    @Test
    void elasticsearchClientFailureShouldEscapeListenerForKafkaRetry() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        when(client.index(any(java.util.function.Function.class)))
                .thenThrow(new IOException("connection reset"));
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        ElasticsearchIndexer indexer = new ElasticsearchIndexer(client, enabledProperties(), new ObjectMapper(),
                jdbcTemplate, mock(com.noteweave.task.TaskService.class));
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "noteweave.source.index",
                0,
                4L,
                "chunk-1",
                """
                        {
                          "workspaceId":"workspace-1",
                          "chunkId":"chunk-1",
                          "sourceId":"source-1",
                          "sourceSnapshotId":"snapshot-1",
                          "chunkNo":0,
                          "title":"Title",
                          "sourceType":"FILE",
                          "content":"Content"
                        }
                        """
        );

        assertThatThrownBy(() -> indexer.onSourceIndexEvent(record))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ES source.index projection failed")
                .hasRootCauseMessage("connection reset");
    }

    @Test
    void sourceShouldBecomeReadyOnlyAfterAllChunksAreProjected() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:es-projection-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table source(
                    id varchar(36) primary key,
                    workspace_id varchar(36),
                    index_status varchar(32),
                    status varchar(32),
                    updated_by varchar(80),
                    updated_at timestamp
                )
                """);
        jdbcTemplate.execute("create table source_snapshot(id varchar(36) primary key, source_id varchar(36), index_status varchar(32))");
        jdbcTemplate.execute("create table source_chunk(id varchar(36) primary key, workspace_id varchar(36), source_id varchar(36), source_snapshot_id varchar(36), projection_status varchar(32), projected_at timestamp)");
        jdbcTemplate.update("""
                insert into source(id, workspace_id, index_status, status, updated_by, updated_at)
                values ('source-1', 'workspace-1', 'INDEXING', 'PROCESSING', 'SYSTEM:TEST', current_timestamp)
                """);
        jdbcTemplate.update("insert into source_snapshot values ('snapshot-1', 'source-1', 'INDEXING')");
        jdbcTemplate.update("insert into source_chunk(id, workspace_id, source_id, source_snapshot_id, projection_status) values ('chunk-1', 'workspace-1', 'source-1', 'snapshot-1', 'PENDING')");
        jdbcTemplate.update("insert into source_chunk(id, workspace_id, source_id, source_snapshot_id, projection_status) values ('chunk-2', 'workspace-1', 'source-1', 'snapshot-1', 'PENDING')");

        ElasticsearchClient client = mock(ElasticsearchClient.class);
        when(client.index(any(java.util.function.Function.class))).thenReturn(mock(IndexResponse.class));
        TaskService taskService = mock(TaskService.class);
        ElasticsearchIndexer indexer = new ElasticsearchIndexer(
                client, enabledProperties(), new ObjectMapper(), jdbcTemplate, taskService,
                new SimpleMeterRegistry(), mock(SourceCatalogVersionService.class));

        indexer.onSourceIndexEvent(indexRecord("chunk-1", 0));
        assertThat(jdbcTemplate.queryForObject("select status from source where id = 'source-1'", String.class))
                .isEqualTo("PROCESSING");

        indexer.onSourceIndexEvent(indexRecord("chunk-2", 1));
        assertThat(jdbcTemplate.queryForObject("select status from source where id = 'source-1'", String.class))
                .isEqualTo("READY");
        assertThat(jdbcTemplate.queryForObject("select index_status from source_snapshot where id = 'snapshot-1'", String.class))
                .isEqualTo("INDEXED");
        verify(taskService).completeTask("task-1", "INDEXED", "资料解析与 Elasticsearch 投影已完成", "source-1");
    }

    @Test
    void deletedSourceShouldSkipProjectionWithoutRestoringState() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:es-deleted-source-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table source(
                    id varchar(36) primary key,
                    workspace_id varchar(36),
                    index_status varchar(32),
                    status varchar(32),
                    updated_by varchar(80),
                    updated_at timestamp
                )
                """);
        jdbcTemplate.execute("create table source_snapshot(id varchar(36) primary key, source_id varchar(36), index_status varchar(32))");
        jdbcTemplate.execute("create table source_chunk(id varchar(36) primary key, workspace_id varchar(36), source_id varchar(36), source_snapshot_id varchar(36), projection_status varchar(32), projected_at timestamp)");
        jdbcTemplate.update("""
                insert into source(id, workspace_id, index_status, status, updated_by, updated_at)
                values ('source-1', 'workspace-1', 'DELETED', 'DELETED', 'SYSTEM:TEST', current_timestamp)
                """);
        jdbcTemplate.update("insert into source_snapshot values ('snapshot-1', 'source-1', 'DELETED')");
        jdbcTemplate.update("insert into source_chunk(id, workspace_id, source_id, source_snapshot_id, projection_status) values ('chunk-1', 'workspace-1', 'source-1', 'snapshot-1', 'PENDING')");

        ElasticsearchClient client = mock(ElasticsearchClient.class);
        TaskService taskService = mock(TaskService.class);
        ElasticsearchIndexer indexer = new ElasticsearchIndexer(
                client, enabledProperties(), new ObjectMapper(), jdbcTemplate, taskService,
                new SimpleMeterRegistry(), mock(SourceCatalogVersionService.class));

        indexer.onSourceIndexEvent(indexRecord("chunk-1", 0));

        verifyNoInteractions(client, taskService);
        assertThat(jdbcTemplate.queryForObject("select status from source where id = 'source-1'", String.class))
                .isEqualTo("DELETED");
        assertThat(jdbcTemplate.queryForObject("select index_status from source_snapshot where id = 'snapshot-1'", String.class))
                .isEqualTo("DELETED");
        assertThat(jdbcTemplate.queryForObject("select projection_status from source_chunk where id = 'chunk-1'", String.class))
                .isEqualTo("PENDING");
    }

    private ConsumerRecord<String, String> indexRecord(String chunkId, int chunkNo) {
        return new ConsumerRecord<>("noteweave.source.index", 0, chunkNo, chunkId, """
                {"taskId":"task-1","workspaceId":"workspace-1","chunkId":"%s","sourceId":"source-1",
                 "sourceSnapshotId":"snapshot-1","chunkNo":%d,"title":"Title","sourceType":"FILE","content":"Content"}
                """.formatted(chunkId, chunkNo));
    }

    private NoteWeaveProperties enabledProperties() {
        return new NoteWeaveProperties(
                null,
                null,
                null,
                null,
                new NoteWeaveProperties.Elasticsearch(true, "localhost", 9200, "http", "noteweave_chunk"),
                null
        );
    }
}
