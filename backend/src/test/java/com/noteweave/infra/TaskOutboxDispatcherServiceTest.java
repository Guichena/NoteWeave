package com.noteweave.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.infra.outbox.DurableOutboxDispatcher;
import com.noteweave.retrieval.projection.SourceRetrievalProjectionFinalizer;
import com.noteweave.source.SourceParseFailureFinalizer;
import com.noteweave.source.SourcePipelineStages;
import com.noteweave.task.TaskService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;

class TaskOutboxDispatcherServiceTest {

    private JdbcTemplate jdbcTemplate;
    private KafkaTemplate<String, String> kafkaTemplate;
    private NoteWeaveProperties properties;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:outbox-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table task_outbox (
                    id varchar(36) primary key, task_id varchar(36), topic varchar(160) not null,
                    message_key varchar(160) not null, payload_json clob not null, status varchar(32) not null,
                    attempt_count int not null default 0, last_error varchar(1000), claimed_at timestamp,
                    next_attempt_at timestamp, lease_owner varchar(128), lease_until timestamp,
                    dead_lettered_at timestamp, sent_at timestamp, created_at timestamp default current_timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table source (
                    id varchar(36) primary key, workspace_id varchar(36) not null, status varchar(32) not null,
                    parse_status varchar(32) not null, index_status varchar(32) not null,
                    updated_by varchar(100), updated_at timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table source_snapshot (
                    id varchar(36) primary key, source_id varchar(36) not null, version_no int not null,
                    parse_status varchar(32) not null, index_status varchar(32) not null,
                    processing_stage varchar(32), embedding_task_id varchar(36), embedding_batch_version varchar(64),
                    index_attempt_count int not null default 0, next_index_retry_at timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table source_embedding_batch (
                    id varchar(36) primary key, source_snapshot_id varchar(36) not null, task_id varchar(36),
                    batch_no int not null default 0, status varchar(32) not null,
                    lease_token varchar(64), lease_until timestamp, updated_at timestamp
                )
                """);
        kafkaTemplate = mock(KafkaTemplate.class);
        properties = new NoteWeaveProperties(null, null, null,
                new NoteWeaveProperties.Kafka(true, new NoteWeaveProperties.Topics(
                        "noteweave.source.parse", "noteweave.retrieval.projection",
                        "noteweave.wiki.ingest", "noteweave.wiki.retract",
                        "noteweave.conversation.summary")),
                null, null);
    }

    @Test
    void competingDispatchersShouldPublishReadyMessageOnce() throws Exception {
        insertOutbox("outbox-1", 0);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        TaskOutboxDispatcherService first = new TaskOutboxDispatcherService(jdbcTemplate, kafkaTemplate, properties, mock(com.noteweave.task.TaskService.class));
        TaskOutboxDispatcherService second = new TaskOutboxDispatcherService(jdbcTemplate, kafkaTemplate, properties, mock(com.noteweave.task.TaskService.class));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(first::dispatchReadyMessages);
            var two = pool.submit(second::dispatchReadyMessages);
            one.get();
            two.get();
        } finally {
            pool.shutdownNow();
        }

        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
        assertThat(status("outbox-1")).isEqualTo("SENT");
    }

    @Test
    void fifthFailureShouldDeadLetterMessage() {
        insertOutbox("outbox-2", 4);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        new TaskOutboxDispatcherService(jdbcTemplate, kafkaTemplate, properties,
                mock(com.noteweave.task.TaskService.class), meterRegistry).dispatchReadyMessages();

        assertThat(status("outbox-2")).isEqualTo("DEAD_LETTER");
        assertThat(jdbcTemplate.queryForObject(
                "select attempt_count from task_outbox where id = ?", Integer.class, "outbox-2"))
                .isEqualTo(5);
        assertThat(meterRegistry.get("noteweave.outbox.dispatch.dead_letter")
                .tag("topic", "noteweave.source.parse").counter().count()).isEqualTo(1.0);
    }

    @Test
    void embedDeliveryExhaustionShouldFinalizeSnapshotAndTaskFailure() {
        TaskService taskService = mock(TaskService.class);
        SourceRetrievalProjectionFinalizer projectionFinalizer = mock(SourceRetrievalProjectionFinalizer.class);
        insertOutbox("outbox-embed", "task-embed", 4, SourcePipelineStages.TOPIC_EMBED, embedPayload());
        failEverySend();

        dispatcher(new SimpleMeterRegistry(), taskService, projectionFinalizer).dispatchReadyMessages();

        assertThat(status("outbox-embed")).isEqualTo("DEAD_LETTER");
        verify(projectionFinalizer).finalizeFailed(
                "workspace-1", "source-1", "snapshot-1", "task-embed", "OUTBOX_DISPATCH_EXHAUSTED");
        verify(taskService, never()).failTask(anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void indexDeliveryExhaustionShouldFinalizeSnapshotAndTaskFailure() {
        TaskService taskService = mock(TaskService.class);
        SourceRetrievalProjectionFinalizer projectionFinalizer = mock(SourceRetrievalProjectionFinalizer.class);
        insertOutbox("outbox-index", "task-index", 4, SourcePipelineStages.TOPIC_INDEX, """
                {"workspaceId":"workspace-1","sourceId":"source-1","sourceSnapshotId":"snapshot-1",
                 "taskId":"task-index","batchVersion":"%s"}
                """.formatted("b".repeat(64)));
        failEverySend();

        dispatcher(new SimpleMeterRegistry(), taskService, projectionFinalizer).dispatchReadyMessages();

        assertThat(status("outbox-index")).isEqualTo("DEAD_LETTER");
        verify(projectionFinalizer).finalizeFailed(
                "workspace-1", "source-1", "snapshot-1", "task-index", "OUTBOX_DISPATCH_EXHAUSTED");
        verify(taskService, never()).failTask(anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void projectionFinalizationFailureShouldNotBreakDispatchLoop() {
        TaskService taskService = mock(TaskService.class);
        SourceRetrievalProjectionFinalizer projectionFinalizer = mock(SourceRetrievalProjectionFinalizer.class);
        doThrow(new IllegalStateException("snapshot is not awaiting failure finalization")).when(projectionFinalizer)
                .finalizeFailed(anyString(), anyString(), anyString(), anyString(), anyString());
        insertOutbox("outbox-embed", "task-embed", 4, SourcePipelineStages.TOPIC_EMBED, embedPayload());
        failEverySend();

        TaskOutboxDispatcherService dispatcher = dispatcher(new SimpleMeterRegistry(), taskService, projectionFinalizer);

        assertThatCode(dispatcher::dispatchReadyMessages).doesNotThrowAnyException();
        assertThat(status("outbox-embed")).isEqualTo("DEAD_LETTER");
        // 收尾没有生效时退回到只打失败任务，任务不会停在执行中
        verify(taskService).failTask("task-embed", "OUTBOX_DEAD_LETTER",
                "Kafka Outbox 投递耗尽，已进入死信", "OUTBOX_DISPATCH_EXHAUSTED", true);
    }

    @Test
    void unrelatedTopicExhaustionShouldStillOnlyFailTask() {
        TaskService taskService = mock(TaskService.class);
        SourceRetrievalProjectionFinalizer projectionFinalizer = mock(SourceRetrievalProjectionFinalizer.class);
        insertOutbox("outbox-wiki", "task-wiki", 4, "noteweave.wiki.ingest", "{\"taskId\":\"task-wiki\"}");
        failEverySend();

        dispatcher(new SimpleMeterRegistry(), taskService, projectionFinalizer).dispatchReadyMessages();

        assertThat(status("outbox-wiki")).isEqualTo("DEAD_LETTER");
        verify(taskService).failTask("task-wiki", "OUTBOX_DEAD_LETTER",
                "Kafka Outbox 投递耗尽，已进入死信", "OUTBOX_DISPATCH_EXHAUSTED", true);
        verify(projectionFinalizer, never()).finalizeFailed(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void redriveOfParseDeadLetterShouldResetSourceAndSnapshotForReparse() {
        insertDeadLetter("outbox-parse", "task-1", SourcePipelineStages.TOPIC_PARSE, """
                {"taskId":"task-1","workspaceId":"workspace-1","sourceId":"source-1","snapshotId":"snapshot-1"}
                """);
        seedSource("FAILED", "FAILED", "FAILED");
        seedSnapshot("snapshot-1", "FAILED", "FAILED", "EXTRACTING", 2, true);
        TaskService taskService = mock(TaskService.class);

        redrive("outbox-parse", taskService);

        assertThat(status("outbox-parse")).isEqualTo("READY");
        assertThat(attemptCount("outbox-parse")).isZero();
        java.util.Map<String, Object> snapshot = snapshot("snapshot-1");
        assertThat(snapshot.get("parse_status")).isEqualTo("PENDING");
        assertThat(snapshot.get("index_status")).isEqualTo("PENDING");
        assertThat(snapshot.get("processing_stage")).isNull();
        assertThat(snapshot.get("embedding_task_id")).isNull();
        assertThat(snapshot.get("embedding_batch_version")).isNull();
        assertThat(snapshot.get("index_attempt_count")).isEqualTo(0);
        assertThat(snapshot.get("next_index_retry_at")).isNull();
        java.util.Map<String, Object> source = source();
        assertThat(source.get("status")).isEqualTo("PROCESSING");
        assertThat(source.get("parse_status")).isEqualTo("PENDING");
        assertThat(source.get("index_status")).isEqualTo("PENDING");
        verify(taskService).redriveTask("task-1", false);
    }

    @Test
    void redriveOfChunkDeadLetterShouldParkSnapshotInChunkingStage() {
        insertDeadLetter("outbox-chunk", "task-1", SourcePipelineStages.TOPIC_CHUNK, """
                {"taskId":"task-1","workspaceId":"workspace-1","sourceId":"source-1","snapshotId":"snapshot-1",
                 "textObjectKey":"workspace/workspace-1/source/source-1/snapshot/snapshot-1/extracted-text.txt"}
                """);
        seedSource("FAILED", "FAILED", "FAILED");
        seedSnapshot("snapshot-1", "FAILED", "FAILED", null, 0, false);
        TaskService taskService = mock(TaskService.class);

        redrive("outbox-chunk", taskService);

        java.util.Map<String, Object> snapshot = snapshot("snapshot-1");
        // 切片消费者只接受 PENDING + CHUNKING，否则重投的消息会被直接确认
        assertThat(snapshot.get("parse_status")).isEqualTo("PENDING");
        assertThat(snapshot.get("processing_stage")).isEqualTo(SourcePipelineStages.STAGE_CHUNKING);
        assertThat(source().get("status")).isEqualTo("PROCESSING");
        verify(taskService).redriveTask("task-1", false);
    }

    @Test
    void redriveOfEmbedDeadLetterShouldResetSnapshotToEmbeddingStage() {
        insertDeadLetter("outbox-embed-redrive", "task-1", SourcePipelineStages.TOPIC_EMBED, """
                {"workspaceId":"workspace-1","sourceId":"source-1","sourceSnapshotId":"snapshot-1",
                 "taskId":"task-1","batchNo":0,"batchVersion":"%s"}
                """.formatted("c".repeat(64)));
        seedSource("FAILED", "PARSED", "FAILED");
        seedSnapshot("snapshot-1", "PARSED", "FAILED", SourcePipelineStages.STAGE_EMBEDDING, 1, true);
        seedBatch("batch-1", "RUNNING", "lease-1");
        TaskService taskService = mock(TaskService.class);

        redrive("outbox-embed-redrive", taskService);

        java.util.Map<String, Object> snapshot = snapshot("snapshot-1");
        assertThat(snapshot.get("parse_status")).isEqualTo("PARSED");
        assertThat(snapshot.get("index_status")).isEqualTo("INDEXING");
        assertThat(snapshot.get("processing_stage")).isEqualTo(SourcePipelineStages.STAGE_EMBEDDING);
        assertThat(snapshot.get("index_attempt_count")).isEqualTo(0);
        assertThat(snapshot.get("next_index_retry_at")).isNull();
        java.util.Map<String, Object> source = source();
        assertThat(source.get("status")).isEqualTo("PROCESSING");
        assertThat(source.get("index_status")).isEqualTo("INDEXING");
        java.util.Map<String, Object> batch = batch("batch-1");
        assertThat(batch.get("status")).isEqualTo("READY");
        assertThat(batch.get("lease_token")).isNull();
        verify(taskService).redriveTask("task-1", false);
    }

    @Test
    void redriveOfIndexDeadLetterShouldResetSnapshotToIndexingStageAndKeepCompletedBatch() {
        insertDeadLetter("outbox-index-redrive", "task-1", SourcePipelineStages.TOPIC_INDEX, """
                {"workspaceId":"workspace-1","sourceId":"source-1","sourceSnapshotId":"snapshot-1",
                 "taskId":"task-1","embeddingsObjectKey":"workspace/workspace-1/source/source-1/snapshot/snapshot-1/e.bin"}
                """);
        seedSource("FAILED", "PARSED", "FAILED");
        seedSnapshot("snapshot-1", "PARSED", "FAILED", SourcePipelineStages.STAGE_INDEXING, 3, false);
        seedBatch("batch-1", "COMPLETED", "lease-1");
        TaskService taskService = mock(TaskService.class);

        redrive("outbox-index-redrive", taskService);

        java.util.Map<String, Object> snapshot = snapshot("snapshot-1");
        assertThat(snapshot.get("index_status")).isEqualTo("INDEXING");
        assertThat(snapshot.get("processing_stage")).isEqualTo(SourcePipelineStages.STAGE_INDEXING);
        assertThat(source().get("index_status")).isEqualTo("INDEXING");
        // 已完成的批次保持 COMPLETED，只释放租约
        java.util.Map<String, Object> batch = batch("batch-1");
        assertThat(batch.get("status")).isEqualTo("COMPLETED");
        assertThat(batch.get("lease_token")).isNull();
        verify(taskService).redriveTask("task-1", false);
    }

    @Test
    void redriveWithoutSourceIdentityShouldOnlyResetOutboxRow() {
        insertDeadLetter("outbox-anonymous", "task-1", SourcePipelineStages.TOPIC_PARSE, "{\"taskId\":\"task-1\"}");
        TaskService taskService = mock(TaskService.class);

        assertThatCode(() -> redrive("outbox-anonymous", taskService)).doesNotThrowAnyException();

        assertThat(status("outbox-anonymous")).isEqualTo("READY");
        assertThat(jdbcTemplate.queryForObject("select count(*) from source", Integer.class)).isZero();
        verify(taskService).redriveTask("task-1", false);
    }

    @Test
    void redriveShouldNotResurrectDeletedSource() {
        insertDeadLetter("outbox-deleted", "task-1", SourcePipelineStages.TOPIC_PARSE, """
                {"taskId":"task-1","workspaceId":"workspace-1","sourceId":"source-1","snapshotId":"snapshot-1"}
                """);
        seedSource("DELETED", "DELETED", "DELETED");
        seedSnapshot("snapshot-1", "DELETED", "DELETED", null, 0, false);
        TaskService taskService = mock(TaskService.class);

        redrive("outbox-deleted", taskService);

        assertThat(snapshot("snapshot-1").get("parse_status")).isEqualTo("DELETED");
        assertThat(source().get("status")).isEqualTo("DELETED");
    }

    private void redrive(String outboxId, TaskService taskService) {
        new TaskOutboxDispatcherService(jdbcTemplate, kafkaTemplate, properties, taskService).redrive(outboxId);
    }

    private void seedSource(String status, String parseStatus, String indexStatus) {
        jdbcTemplate.update("""
                insert into source(id, workspace_id, status, parse_status, index_status)
                values ('source-1', 'workspace-1', ?, ?, ?)
                """, status, parseStatus, indexStatus);
    }

    private void seedSnapshot(
            String snapshotId, String parseStatus, String indexStatus, String processingStage,
            int indexAttemptCount, boolean nextIndexRetryPending
    ) {
        jdbcTemplate.update("""
                insert into source_snapshot(id, source_id, version_no, parse_status, index_status, processing_stage,
                    embedding_task_id, embedding_batch_version, index_attempt_count, next_index_retry_at)
                values (?, 'source-1', 1, ?, ?, ?, 'task-1', 'batch-version-1', ?, ?)
                """, snapshotId, parseStatus, indexStatus, processingStage, indexAttemptCount,
                nextIndexRetryPending ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
    }

    private void seedBatch(String batchId, String batchStatus, String leaseToken) {
        jdbcTemplate.update("""
                insert into source_embedding_batch(id, source_snapshot_id, task_id, batch_no, status, lease_token,
                    lease_until, updated_at)
                values (?, 'snapshot-1', 'task-1', 0, ?, ?, current_timestamp, current_timestamp)
                """, batchId, batchStatus, leaseToken);
    }

    private java.util.Map<String, Object> snapshot(String snapshotId) {
        return jdbcTemplate.queryForMap("select * from source_snapshot where id = ?", snapshotId);
    }

    private java.util.Map<String, Object> source() {
        return jdbcTemplate.queryForMap("select * from source where id = 'source-1'");
    }

    private java.util.Map<String, Object> batch(String batchId) {
        return jdbcTemplate.queryForMap("select * from source_embedding_batch where id = ?", batchId);
    }

    private int attemptCount(String outboxId) {
        return jdbcTemplate.queryForObject(
                "select attempt_count from task_outbox where id = ?", Integer.class, outboxId);
    }

    private void insertDeadLetter(String id, String taskId, String topic, String payloadJson) {
        insertOutbox(id, taskId, 5, topic, payloadJson);
        jdbcTemplate.update("""
                update task_outbox set status = 'DEAD_LETTER', dead_lettered_at = current_timestamp
                where id = ?
                """, id);
    }

    private TaskOutboxDispatcherService dispatcher(
            MeterRegistry meterRegistry,
            TaskService taskService,
            SourceRetrievalProjectionFinalizer projectionFinalizer
    ) {
        return new TaskOutboxDispatcherService(jdbcTemplate, kafkaTemplate, properties, taskService, meterRegistry,
                new DurableOutboxDispatcher(jdbcTemplate, meterRegistry),
                mock(SourceParseFailureFinalizer.class), projectionFinalizer);
    }

    private void failEverySend() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
    }

    private String embedPayload() {
        return """
                {"workspaceId":"workspace-1","sourceId":"source-1","sourceSnapshotId":"snapshot-1",
                 "taskId":"task-embed","batchNo":0,"batchVersion":"%s"}
                """.formatted("c".repeat(64));
    }

    private void insertOutbox(String id, int attempts) {
        insertOutbox(id, "task-1", attempts, "noteweave.source.parse", "source-1", "{}");
    }

    private void insertOutbox(String id, String taskId, int attempts, String topic, String payloadJson) {
        insertOutbox(id, taskId, attempts, topic, taskId, payloadJson);
    }

    private void insertOutbox(String id, String taskId, int attempts, String topic, String messageKey, String payloadJson) {
        jdbcTemplate.update("""
                insert into task_outbox(id, task_id, topic, message_key, payload_json, status, attempt_count)
                values (?, ?, ?, ?, ?, 'READY', ?)
                """, id, taskId, topic, messageKey, payloadJson, attempts);
    }

    private String status(String id) {
        return jdbcTemplate.queryForObject(
                "select status from task_outbox where id = ?", String.class, id);
    }
}
