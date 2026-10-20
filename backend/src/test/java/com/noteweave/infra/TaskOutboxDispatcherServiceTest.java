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
