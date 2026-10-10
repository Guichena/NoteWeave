package com.noteweave.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.retrieval.projection.SourceRetrievalProjectionFinalizer;
import com.noteweave.source.SourceCatalogVersionService;
import com.noteweave.source.SourceParseFailureFinalizer;
import com.noteweave.task.TaskService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 资料解析消息重试耗尽进入 DLT 后，资料、快照和任务必须一起进入失败终态。 */
class KafkaSourceParseDeadLetterRecoveryTest {

    private static final String PAYLOAD = """
            {"taskId":"task-parse-1","workspaceId":"workspace-1",
             "sourceId":"source-parse-1","snapshotId":"snapshot-parse-1"}
            """;

    @Test
    void documentFailureShouldFinalizeWithItsBusinessCodeAsNotRetryable() {
        SourceParseFailureFinalizer finalizer = mock(SourceParseFailureFinalizer.class);
        KafkaDeadLetterTaskRecovery recovery = recovery(finalizer);
        // 与 KafkaTaskConsumer.handle 和 SourceParseService 的包装层次一致
        Exception failure = new IllegalStateException("Kafka source.parse handler failed",
                new IllegalStateException("async source parse failed for sourceId=source-parse-1",
                        new BusinessException("SOURCE_PDF_TEXT_EMPTY", "PDF 未提取到可检索文本，请先对扫描件执行 OCR：扫描件.pdf")));

        recovery.recover(record("source.parse", PAYLOAD), failure);

        verify(finalizer).finalizeProcessingFailed(
                "task-parse-1", "workspace-1", "source-parse-1", "snapshot-parse-1",
                "SOURCE_PDF_TEXT_EMPTY", "PDF 未提取到可检索文本，请先对扫描件执行 OCR：扫描件.pdf", false);
    }

    @Test
    void infrastructureFailureShouldFinalizeAsRetryable() {
        SourceParseFailureFinalizer finalizer = mock(SourceParseFailureFinalizer.class);

        recovery(finalizer).recover(record("source.parse", PAYLOAD),
                new IllegalStateException("handler failed", new java.io.UncheckedIOException(new java.io.IOException("minio timeout"))));

        verify(finalizer).finalizeProcessingFailed(
                "task-parse-1", "workspace-1", "source-parse-1", "snapshot-parse-1",
                "SOURCE_PARSE_FAILED", "资料解析过程中出错", true);
    }

    @Test
    void recoveryMustNotThrowSoTheDeadLetterIsNotRedelivered() {
        SourceParseFailureFinalizer finalizer = mock(SourceParseFailureFinalizer.class);
        doThrow(new BusinessException("SOURCE_PARSE_OUTBOX_IDENTITY_MISMATCH", "mismatch"))
                .when(finalizer).finalizeProcessingFailed(
                        anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
        KafkaDeadLetterTaskRecovery recovery = recovery(finalizer);

        assertThatCode(() -> recovery.recover(record("source.parse", PAYLOAD), new IllegalStateException("boom")))
                .doesNotThrowAnyException();
        assertThatCode(() -> recovery.recover(record("source.parse", "not json"), new IllegalStateException("boom")))
                .doesNotThrowAnyException();
        assertThatCode(() -> recovery.recover(record("source.parse", "{\"taskId\":\"task-parse-1\"}"),
                new IllegalStateException("boom"))).doesNotThrowAnyException();
    }

    @Test
    void otherTopicsMustNotTouchSourceParseState() {
        SourceParseFailureFinalizer finalizer = mock(SourceParseFailureFinalizer.class);

        recovery(finalizer).recover(record("wiki.ingest", PAYLOAD), new IllegalStateException("boom"));

        verify(finalizer, never()).finalizeProcessingFailed(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void finalizerShouldFailPendingSnapshotCurrentSourceAndRunningTask() {
        JdbcTemplate jdbcTemplate = sourceSchema("parse-consumer-failure");
        jdbcTemplate.update("""
                insert into source(id, workspace_id, status, parse_status, index_status)
                values ('source-parse-1', 'workspace-1', 'PROCESSING', 'PENDING', 'PENDING')
                """);
        jdbcTemplate.update("""
                insert into source_snapshot(id, source_id, version_no, parse_status, index_status)
                values ('snapshot-parse-1', 'source-parse-1', 1, 'PENDING', 'PENDING')
                """);
        TaskService taskService = taskService("RUNNING");
        SourceCatalogVersionService catalogVersionService = mock(SourceCatalogVersionService.class);
        SourceParseFailureFinalizer finalizer = new SourceParseFailureFinalizer(
                jdbcTemplate, new ObjectMapper(), taskService, catalogVersionService);

        finalizer.finalizeProcessingFailed("task-parse-1", "workspace-1", "source-parse-1", "snapshot-parse-1",
                "SOURCE_PDF_TEXT_EMPTY", "PDF 未提取到可检索文本", false);

        assertThat(jdbcTemplate.queryForMap("select status, parse_status, index_status, updated_by from source"))
                .containsEntry("STATUS", "FAILED")
                .containsEntry("PARSE_STATUS", "FAILED")
                .containsEntry("INDEX_STATUS", "FAILED")
                .containsEntry("UPDATED_BY", "SYSTEM:SOURCE_PARSE_CONSUMER");
        assertThat(jdbcTemplate.queryForMap("select parse_status, index_status from source_snapshot"))
                .containsEntry("PARSE_STATUS", "FAILED")
                .containsEntry("INDEX_STATUS", "FAILED");
        verify(taskService).failTask("task-parse-1", "SOURCE_PARSE_FAILED",
                "资料解析失败：PDF 未提取到可检索文本", "SOURCE_PDF_TEXT_EMPTY", false);
        verify(catalogVersionService).bump("workspace-1");
    }

    @Test
    void repeatedDeadLetterForTerminalTaskShouldBeIgnored() {
        JdbcTemplate jdbcTemplate = sourceSchema("parse-consumer-repeat");
        jdbcTemplate.update("""
                insert into source(id, workspace_id, status, parse_status, index_status)
                values ('source-parse-1', 'workspace-1', 'FAILED', 'FAILED', 'FAILED')
                """);
        jdbcTemplate.update("""
                insert into source_snapshot(id, source_id, version_no, parse_status, index_status)
                values ('snapshot-parse-1', 'source-parse-1', 1, 'FAILED', 'FAILED')
                """);
        TaskService taskService = taskService("FAILED");
        SourceCatalogVersionService catalogVersionService = mock(SourceCatalogVersionService.class);
        SourceParseFailureFinalizer finalizer = new SourceParseFailureFinalizer(
                jdbcTemplate, new ObjectMapper(), taskService, catalogVersionService);

        finalizer.finalizeProcessingFailed("task-parse-1", "workspace-1", "source-parse-1", "snapshot-parse-1",
                "SOURCE_PDF_TEXT_EMPTY", "PDF 未提取到可检索文本", false);

        verify(taskService, never()).lockTaskRef(anyString());
        verify(taskService, never()).failTask(anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(catalogVersionService, never()).bump(anyString());
    }

    @Test
    void staleSnapshotFailureMustNotOverwriteANewerSourceVersion() {
        JdbcTemplate jdbcTemplate = sourceSchema("parse-consumer-stale");
        jdbcTemplate.update("""
                insert into source(id, workspace_id, status, parse_status, index_status)
                values ('source-parse-1', 'workspace-1', 'READY', 'PARSED', 'INDEXED')
                """);
        jdbcTemplate.update("""
                insert into source_snapshot(id, source_id, version_no, parse_status, index_status) values
                ('snapshot-parse-1', 'source-parse-1', 1, 'PENDING', 'PENDING'),
                ('snapshot-parse-2', 'source-parse-1', 2, 'PARSED', 'INDEXED')
                """);
        TaskService taskService = taskService("RUNNING");
        SourceCatalogVersionService catalogVersionService = mock(SourceCatalogVersionService.class);
        SourceParseFailureFinalizer finalizer = new SourceParseFailureFinalizer(
                jdbcTemplate, new ObjectMapper(), taskService, catalogVersionService);

        finalizer.finalizeProcessingFailed("task-parse-1", "workspace-1", "source-parse-1", "snapshot-parse-1",
                "SOURCE_PARSE_FAILED", "资料解析过程中出错", true);

        assertThat(jdbcTemplate.queryForMap("select status, parse_status, index_status from source"))
                .containsEntry("STATUS", "READY")
                .containsEntry("PARSE_STATUS", "PARSED")
                .containsEntry("INDEX_STATUS", "INDEXED");
        assertThat(jdbcTemplate.queryForObject(
                "select parse_status from source_snapshot where id = 'snapshot-parse-1'", String.class))
                .isEqualTo("FAILED");
        verify(taskService).failTask("task-parse-1", "SOURCE_PARSE_FAILED",
                "资料解析失败：资料解析过程中出错", "SOURCE_PARSE_FAILED", true);
        verify(catalogVersionService, never()).bump(anyString());
    }

    private static KafkaDeadLetterTaskRecovery recovery(SourceParseFailureFinalizer finalizer) {
        return new KafkaDeadLetterTaskRecovery(new ObjectMapper(), mock(SourceRetrievalProjectionFinalizer.class),
                finalizer, "retrieval.projection", "source.parse");
    }

    private static ConsumerRecord<String, String> record(String topic, String value) {
        return new ConsumerRecord<>(topic, 0, 3L, "snapshot-parse-1", value);
    }

    private static TaskService taskService(String status) {
        TaskService taskService = mock(TaskService.class);
        TaskService.TaskRef ref = new TaskService.TaskRef(
                "task-parse-1", "workspace-1", "SOURCE_PARSE", status, "SOURCE", "source-parse-1");
        when(taskService.getTaskRef("task-parse-1")).thenReturn(ref);
        when(taskService.lockTaskRef("task-parse-1")).thenReturn(ref);
        return taskService;
    }

    private static JdbcTemplate sourceSchema(String name) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + name + "-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
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
        return jdbcTemplate;
    }
}
