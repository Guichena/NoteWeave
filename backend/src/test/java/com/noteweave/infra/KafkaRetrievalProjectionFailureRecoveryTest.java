package com.noteweave.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.retrieval.projection.RetrievalProjectionRepository;
import com.noteweave.retrieval.projection.SourceRetrievalProjectionCoordinator;
import com.noteweave.retrieval.projection.SourceRetrievalProjectionFinalizer;
import com.noteweave.retrieval.projection.SynchronousRetrievalProjectionListener;
import com.noteweave.retrieval.provider.RetrievalProviderException;
import com.noteweave.source.SourceCatalogVersionService;
import com.noteweave.source.SourceParseFailureFinalizer;
import com.noteweave.source.SourceParseService.SynchronousRetrievalProjectionRequested;
import com.noteweave.task.TaskService;
import java.util.Collection;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.util.ReflectionTestUtils;

class KafkaRetrievalProjectionFailureRecoveryTest {

    @Test
    void kafkaConfigShouldProvisionEveryDeadLetterTopic() {
        KafkaAdmin.NewTopics topics = new KafkaConfig().kafkaDeadLetterTopics(
                "source.parse", "retrieval.projection", "wiki.ingest", "wiki.retract",
                "conversation.summary", "artifact.job.dlq");

        @SuppressWarnings("unchecked")
        Collection<NewTopic> definitions = (Collection<NewTopic>) ReflectionTestUtils.invokeMethod(
                topics, "getNewTopics");

        assertThat(definitions)
                .extracting(NewTopic::name)
                .containsExactlyInAnyOrder(
                        "source.parse.DLT",
                        "retrieval.projection.DLT",
                        "wiki.ingest.DLT",
                        "wiki.retract.DLT",
                        "conversation.summary.DLT",
                        "noteweave.artifact.job",
                        "artifact.job.dlq"
                );
        assertThat(definitions).allSatisfy(topic -> assertThat(topic.numPartitions()).isEqualTo(1));
    }

    @Test
    void retrievalProjectionDeadLetterShouldPreserveProviderCodeAndFinalizeFailure() {
        SourceRetrievalProjectionFinalizer finalizer = mock(SourceRetrievalProjectionFinalizer.class);
        KafkaDeadLetterTaskRecovery recovery = new KafkaDeadLetterTaskRecovery(
                new ObjectMapper(), finalizer, "retrieval.projection");
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "retrieval.projection",
                0,
                7L,
                "snapshot-1",
                """
                {"workspaceId":"workspace-1","sourceId":"source-1",
                 "sourceSnapshotId":"snapshot-1","taskId":"task-1"}
                """
        );
        Exception failure = new IllegalStateException(
                "handler failed",
                new RetrievalProviderException(
                        "EMBEDDING_PROVIDER_DISABLED",
                        "Source retrieval projection requires embedding"
                )
        );

        recovery.recover(record, failure);

        verify(finalizer).finalizeFailed(
                "workspace-1",
                "source-1",
                "snapshot-1",
                "task-1",
                "EMBEDDING_PROVIDER_DISABLED"
        );
    }

    @Test
    void exhaustedProjectionShouldMakeSourceSnapshotAndTaskTerminal() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:projection-failure-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        createProjectionSchema(jdbcTemplate);
        jdbcTemplate.update("""
                insert into source(id, workspace_id, status, index_status)
                values ('source-1', 'workspace-1', 'PROCESSING', 'INDEXING')
                """);
        jdbcTemplate.update("""
                insert into source_snapshot(id, source_id, version_no, parse_status, index_status)
                values ('snapshot-1', 'source-1', 1, 'PARSED', 'INDEXING')
                """);
        jdbcTemplate.update("""
                insert into source_chunk(id, workspace_id, source_id, source_snapshot_id, projection_status)
                values ('chunk-1', 'workspace-1', 'source-1', 'snapshot-1', 'PENDING')
                """);
        TaskService taskService = mock(TaskService.class);
        TaskService.TaskRef taskRef = new TaskService.TaskRef(
                "task-1", "workspace-1", "SOURCE_PARSE", "RUNNING", "SOURCE", "source-1");
        when(taskService.getTaskRef("task-1")).thenReturn(taskRef);
        when(taskService.lockTaskRef("task-1")).thenReturn(taskRef);
        SourceCatalogVersionService catalogVersionService = mock(SourceCatalogVersionService.class);
        SourceRetrievalProjectionFinalizer finalizer = new SourceRetrievalProjectionFinalizer(
                jdbcTemplate,
                mock(RetrievalProjectionRepository.class),
                catalogVersionService,
                taskService
        );

        finalizer.finalizeFailed(
                "workspace-1", "source-1", "snapshot-1", "task-1", "embedding_provider_disabled");

        assertThat(jdbcTemplate.queryForMap("select status, index_status from source where id = 'source-1'"))
                .containsEntry("STATUS", "FAILED")
                .containsEntry("INDEX_STATUS", "FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "select index_status from source_snapshot where id = 'snapshot-1'", String.class))
                .isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "select projection_status from source_chunk where id = 'chunk-1'", String.class))
                .isEqualTo("FAILED");
        verify(taskService).failTask(
                "task-1",
                "INDEX_FAILED",
                "资料解析已完成，但检索索引生成失败",
                "EMBEDDING_PROVIDER_DISABLED",
                false
        );
        verify(catalogVersionService).bump("workspace-1");
    }

    @Test
    void lateFailureMustNotCorruptReadySourceOrSnapshot() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:late-projection-failure-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        createProjectionSchema(jdbcTemplate);
        jdbcTemplate.update("""
                insert into source(id, workspace_id, status, index_status)
                values ('source-ready-1', 'workspace-1', 'READY', 'INDEXED')
                """);
        jdbcTemplate.update("""
                insert into source_snapshot(id, source_id, version_no, parse_status, index_status)
                values ('snapshot-ready-1', 'source-ready-1', 1, 'PARSED', 'INDEXED')
                """);
        jdbcTemplate.update("""
                insert into source_chunk(id, workspace_id, source_id, source_snapshot_id, projection_status)
                values ('chunk-ready-1', 'workspace-1', 'source-ready-1', 'snapshot-ready-1', 'PROJECTED')
                """);
        TaskService taskService = mock(TaskService.class);
        TaskService.TaskRef taskRef = new TaskService.TaskRef(
                "task-ready-1", "workspace-1", "SOURCE_PARSE", "COMPLETED", "SOURCE", "source-ready-1");
        when(taskService.getTaskRef("task-ready-1")).thenReturn(taskRef);
        when(taskService.lockTaskRef("task-ready-1")).thenReturn(taskRef);
        SourceCatalogVersionService catalogVersionService = mock(SourceCatalogVersionService.class);
        SourceRetrievalProjectionFinalizer finalizer = new SourceRetrievalProjectionFinalizer(
                jdbcTemplate,
                mock(RetrievalProjectionRepository.class),
                catalogVersionService,
                taskService
        );

        finalizer.finalizeFailed(
                "workspace-1", "source-ready-1", "snapshot-ready-1", "task-ready-1", "late_failure");

        assertThat(jdbcTemplate.queryForMap("select status, index_status from source where id = 'source-ready-1'"))
                .containsEntry("STATUS", "READY")
                .containsEntry("INDEX_STATUS", "INDEXED");
        assertThat(jdbcTemplate.queryForObject(
                "select index_status from source_snapshot where id = 'snapshot-ready-1'", String.class))
                .isEqualTo("INDEXED");
        assertThat(jdbcTemplate.queryForObject(
                "select projection_status from source_chunk where id = 'chunk-ready-1'", String.class))
                .isEqualTo("PROJECTED");
        verify(catalogVersionService, org.mockito.Mockito.never()).bump(anyString());
        verify(taskService, org.mockito.Mockito.never()).failTask(
                eq("task-ready-1"), anyString(), anyString(), anyString(), eq(false));
    }

    @Test
    void mismatchedTaskMustNotMutateSourceProjectionState() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:mismatched-projection-task-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        createProjectionSchema(jdbcTemplate);
        jdbcTemplate.update("""
                insert into source(id, workspace_id, status, index_status)
                values ('source-target-1', 'workspace-1', 'PROCESSING', 'INDEXING')
                """);
        jdbcTemplate.update("""
                insert into source_snapshot(id, source_id, version_no, parse_status, index_status)
                values ('snapshot-target-1', 'source-target-1', 1, 'PARSED', 'INDEXING')
                """);
        jdbcTemplate.update("""
                insert into source_chunk(id, workspace_id, source_id, source_snapshot_id, projection_status)
                values ('chunk-target-1', 'workspace-1', 'source-target-1', 'snapshot-target-1', 'PENDING')
                """);
        TaskService taskService = mock(TaskService.class);
        TaskService.TaskRef taskRef = new TaskService.TaskRef(
                "task-other-1", "workspace-1", "SOURCE_PARSE", "RUNNING", "SOURCE", "source-other-1");
        when(taskService.getTaskRef("task-other-1")).thenReturn(taskRef);
        when(taskService.lockTaskRef("task-other-1")).thenReturn(taskRef);
        SourceCatalogVersionService catalogVersionService = mock(SourceCatalogVersionService.class);
        SourceRetrievalProjectionFinalizer finalizer = new SourceRetrievalProjectionFinalizer(
                jdbcTemplate,
                mock(RetrievalProjectionRepository.class),
                catalogVersionService,
                taskService
        );

        assertThatThrownBy(() -> finalizer.finalizeFailed(
                "workspace-1", "source-target-1", "snapshot-target-1", "task-other-1", "provider_failed"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("does not match");

        assertThat(jdbcTemplate.queryForMap("select status, index_status from source where id = 'source-target-1'"))
                .containsEntry("STATUS", "PROCESSING")
                .containsEntry("INDEX_STATUS", "INDEXING");
        assertThat(jdbcTemplate.queryForObject(
                "select index_status from source_snapshot where id = 'snapshot-target-1'", String.class))
                .isEqualTo("INDEXING");
        assertThat(jdbcTemplate.queryForObject(
                "select projection_status from source_chunk where id = 'chunk-target-1'", String.class))
                .isEqualTo("PENDING");
        verify(catalogVersionService, org.mockito.Mockito.never()).bump(anyString());
        verify(taskService, org.mockito.Mockito.never()).failTask(
                eq("task-other-1"), anyString(), anyString(), anyString(), eq(false));
    }

    @Test
    void lateReadyProjectionMustNotResurrectFailedSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:late-projection-success-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        createProjectionSchema(jdbcTemplate);
        jdbcTemplate.update("""
                insert into source(id, workspace_id, status, index_status)
                values ('source-1', 'workspace-1', 'FAILED', 'FAILED')
                """);
        jdbcTemplate.update("""
                insert into source_snapshot(id, source_id, version_no, parse_status, index_status)
                values ('snapshot-1', 'source-1', 1, 'PARSED', 'FAILED')
                """);
        SourceCatalogVersionService catalogVersionService = mock(SourceCatalogVersionService.class);
        TaskService taskService = mock(TaskService.class);
        SourceRetrievalProjectionFinalizer finalizer = new SourceRetrievalProjectionFinalizer(
                jdbcTemplate,
                mock(RetrievalProjectionRepository.class),
                catalogVersionService,
                taskService
        );
        var result = new com.noteweave.retrieval.projection.SourceRetrievalProjectionService.ProjectionResult(
                1, 1, true, "qa-index", "note-index", "embedding-v1");

        assertThatThrownBy(() -> finalizer.finalizeReady(
                "workspace-1", "source-1", "snapshot-1", "task-1", result))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not awaiting");

        assertThat(jdbcTemplate.queryForMap("select status, index_status from source where id = 'source-1'"))
                .containsEntry("STATUS", "FAILED")
                .containsEntry("INDEX_STATUS", "FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "select index_status from source_snapshot where id = 'snapshot-1'", String.class))
                .isEqualTo("FAILED");
        verify(catalogVersionService, org.mockito.Mockito.never()).bump(anyString());
    }

    @Test
    void exhaustedSourceParseDeliveryShouldMakeSourceSnapshotAndTaskTerminal() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:parse-delivery-failure-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", ""
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table source (
                    id varchar(36) primary key, workspace_id varchar(36), status varchar(32),
                    parse_status varchar(32), index_status varchar(32), updated_by varchar(100), updated_at timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table source_snapshot (
                    id varchar(36) primary key, source_id varchar(36), version_no int,
                    parse_status varchar(32), index_status varchar(32)
                )
                """);
        jdbcTemplate.update("""
                insert into source(id, workspace_id, status, parse_status, index_status)
                values ('source-parse-1', 'workspace-1', 'PROCESSING', 'PARSING_QUEUED', 'INDEX_QUEUED')
                """);
        jdbcTemplate.update("""
                insert into source_snapshot(id, source_id, version_no, parse_status, index_status)
                values ('snapshot-parse-1', 'source-parse-1', 1, 'PENDING', 'PENDING')
                """);
        TaskService taskService = mock(TaskService.class);
        when(taskService.getTaskRef("task-parse-1")).thenReturn(new TaskService.TaskRef(
                "task-parse-1", "workspace-1", "SOURCE_PARSE", "RUNNING", "SOURCE", "source-parse-1"));
        when(taskService.lockTaskRef("task-parse-1")).thenReturn(new TaskService.TaskRef(
                "task-parse-1", "workspace-1", "SOURCE_PARSE", "RUNNING", "SOURCE", "source-parse-1"));
        SourceCatalogVersionService catalogVersionService = mock(SourceCatalogVersionService.class);
        SourceParseFailureFinalizer finalizer = new SourceParseFailureFinalizer(
                jdbcTemplate, new ObjectMapper(), taskService, catalogVersionService);

        finalizer.finalizeDeliveryExhausted("task-parse-1", """
                {"taskId":"task-parse-1","workspaceId":"workspace-1",
                 "sourceId":"source-parse-1","snapshotId":"snapshot-parse-1"}
                """);

        assertThat(jdbcTemplate.queryForMap("select status, parse_status, index_status from source"))
                .containsEntry("STATUS", "FAILED")
                .containsEntry("PARSE_STATUS", "FAILED")
                .containsEntry("INDEX_STATUS", "FAILED");
        assertThat(jdbcTemplate.queryForMap("select parse_status, index_status from source_snapshot"))
                .containsEntry("PARSE_STATUS", "FAILED")
                .containsEntry("INDEX_STATUS", "FAILED");
        verify(taskService).failTask(
                "task-parse-1", "OUTBOX_DEAD_LETTER", "Kafka Source Parse 投递耗尽，资料解析未执行",
                "OUTBOX_DISPATCH_EXHAUSTED", true);
        verify(catalogVersionService).bump("workspace-1");
    }

    @Test
    void exhaustedStaleSourceParseDeliveryMustNotOverwriteNewSourceVersion() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:stale-parse-delivery-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", ""
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table source (
                    id varchar(36) primary key, workspace_id varchar(36), status varchar(32),
                    parse_status varchar(32), index_status varchar(32), updated_by varchar(100), updated_at timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table source_snapshot (
                    id varchar(36) primary key, source_id varchar(36), version_no int,
                    parse_status varchar(32), index_status varchar(32)
                )
                """);
        jdbcTemplate.update("""
                insert into source(id, workspace_id, status, parse_status, index_status)
                values ('source-stale-1', 'workspace-1', 'READY', 'PARSED', 'INDEXED')
                """);
        jdbcTemplate.update("""
                insert into source_snapshot(id, source_id, version_no, parse_status, index_status) values
                ('snapshot-old', 'source-stale-1', 1, 'PENDING', 'PENDING'),
                ('snapshot-current', 'source-stale-1', 2, 'PARSED', 'INDEXED')
                """);
        TaskService taskService = mock(TaskService.class);
        when(taskService.getTaskRef("task-stale-1")).thenReturn(new TaskService.TaskRef(
                "task-stale-1", "workspace-1", "SOURCE_PARSE", "RUNNING", "SOURCE", "source-stale-1"));
        when(taskService.lockTaskRef("task-stale-1")).thenReturn(new TaskService.TaskRef(
                "task-stale-1", "workspace-1", "SOURCE_PARSE", "RUNNING", "SOURCE", "source-stale-1"));
        SourceCatalogVersionService catalogVersionService = mock(SourceCatalogVersionService.class);
        SourceParseFailureFinalizer finalizer = new SourceParseFailureFinalizer(
                jdbcTemplate, new ObjectMapper(), taskService, catalogVersionService);

        finalizer.finalizeDeliveryExhausted("task-stale-1", """
                {"taskId":"task-stale-1","workspaceId":"workspace-1",
                 "sourceId":"source-stale-1","snapshotId":"snapshot-old"}
                """);

        assertThat(jdbcTemplate.queryForMap("select status, parse_status, index_status from source"))
                .containsEntry("STATUS", "READY")
                .containsEntry("PARSE_STATUS", "PARSED")
                .containsEntry("INDEX_STATUS", "INDEXED");
        assertThat(jdbcTemplate.queryForObject(
                "select parse_status from source_snapshot where id = 'snapshot-old'", String.class))
                .isEqualTo("FAILED");
        verify(catalogVersionService, org.mockito.Mockito.never()).bump(anyString());
    }

    @Test
    void synchronousProjectionFailureShouldUseTheSameTerminalFinalizer() {
        SourceRetrievalProjectionCoordinator coordinator = mock(SourceRetrievalProjectionCoordinator.class);
        SourceRetrievalProjectionFinalizer finalizer = mock(SourceRetrievalProjectionFinalizer.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(coordinator.projectAndFinalize("workspace-1", "source-1", "snapshot-1", "task-1"))
                .thenThrow(new RetrievalProviderException("EMBEDDING_PROVIDER_DISABLED", "disabled"));
        SynchronousRetrievalProjectionListener listener = new SynchronousRetrievalProjectionListener(
                coordinator, finalizer, jdbcTemplate);

        listener.onRequested(new SynchronousRetrievalProjectionRequested(
                "outbox-1", "task-1", "workspace-1", "source-1", "snapshot-1"));

        verify(finalizer).finalizeFailed(
                "workspace-1", "source-1", "snapshot-1", "task-1", "RETRIEVAL_PROJECTION_FAILED");
        verify(jdbcTemplate).update(
                anyString(),
                eq("RETRIEVAL_PROJECTION_FAILED"),
                eq("outbox-1")
        );
    }

    private void createProjectionSchema(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
                create table source (
                    id varchar(64) primary key,
                    workspace_id varchar(64) not null,
                    status varchar(32) not null,
                    index_status varchar(32) not null,
                    updated_by varchar(120),
                    updated_at timestamp default current_timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table source_snapshot (
                    id varchar(64) primary key,
                    source_id varchar(64) not null,
                    version_no int not null,
                    parse_status varchar(32) not null,
                    index_status varchar(32) not null
                )
                """);
        jdbcTemplate.execute("""
                create table source_chunk (
                    id varchar(64) primary key,
                    workspace_id varchar(64) not null,
                    source_id varchar(64) not null,
                    source_snapshot_id varchar(64) not null,
                    projection_status varchar(32) not null,
                    projected_at timestamp
                )
                """);
    }
}
