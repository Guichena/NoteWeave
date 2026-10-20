package com.noteweave.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.common.BusinessException;
import com.noteweave.task.TaskService;
import com.noteweave.infra.outbox.DurableOutboxDispatcher;
import com.noteweave.infra.outbox.OutboxDispatchPolicy;
import com.noteweave.retrieval.projection.RetrievalProjectionDeadLetterFinalizer;
import com.noteweave.retrieval.projection.SourceEmbeddingBatchRepository;
import com.noteweave.retrieval.projection.SourceRetrievalProjectionFinalizer;
import com.noteweave.source.SourceParseFailureFinalizer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskOutboxDispatcherService {
    private static final Logger log = LoggerFactory.getLogger(TaskOutboxDispatcherService.class);

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final boolean enabled;
    private final List<String> topics;
    private final String sourceParseTopic;
    private final TaskService taskService;
    private final DurableOutboxDispatcher outboxDispatcher;
    private final SourceParseFailureFinalizer sourceParseFailureFinalizer;
    private final RetrievalProjectionDeadLetterFinalizer retrievalProjectionFinalizer;

    public TaskOutboxDispatcherService(
            JdbcTemplate jdbcTemplate,
            KafkaTemplate<String, String> kafkaTemplate,
            NoteWeaveProperties properties,
            TaskService taskService
    ) {
        this(jdbcTemplate, kafkaTemplate, properties, taskService, new SimpleMeterRegistry());
    }

    public TaskOutboxDispatcherService(
            JdbcTemplate jdbcTemplate,
            KafkaTemplate<String, String> kafkaTemplate,
            NoteWeaveProperties properties,
            TaskService taskService,
            MeterRegistry meterRegistry
    ) {
        this(jdbcTemplate, kafkaTemplate, properties, taskService, meterRegistry,
                new DurableOutboxDispatcher(jdbcTemplate, meterRegistry), null, null);
    }

    public TaskOutboxDispatcherService(
            JdbcTemplate jdbcTemplate,
            KafkaTemplate<String, String> kafkaTemplate,
            NoteWeaveProperties properties,
            TaskService taskService,
            MeterRegistry meterRegistry,
            DurableOutboxDispatcher outboxDispatcher,
            SourceParseFailureFinalizer sourceParseFailureFinalizer
    ) {
        this(jdbcTemplate, kafkaTemplate, properties, taskService, meterRegistry,
                outboxDispatcher, sourceParseFailureFinalizer, null);
    }

    @Autowired
    public TaskOutboxDispatcherService(
            JdbcTemplate jdbcTemplate,
            KafkaTemplate<String, String> kafkaTemplate,
            NoteWeaveProperties properties,
            TaskService taskService,
            MeterRegistry meterRegistry,
            DurableOutboxDispatcher outboxDispatcher,
            SourceParseFailureFinalizer sourceParseFailureFinalizer,
            SourceRetrievalProjectionFinalizer sourceRetrievalProjectionFinalizer
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.enabled = properties.kafka().enabled();
        this.taskService = taskService;
        this.outboxDispatcher = outboxDispatcher;
        this.sourceParseFailureFinalizer = sourceParseFailureFinalizer;
        NoteWeaveProperties.Topics configured = properties.kafka().topics();
        this.sourceParseTopic = configured.sourceParse();
        this.topics = configured.all();
        // 向量化/索引投递耗尽要与死信收尾一致：把快照、资料和任务标记为失败，
        // 资料才能被重新处理，残留的批次租约在重新处理时被清理
        this.retrievalProjectionFinalizer = sourceRetrievalProjectionFinalizer == null ? null
                : new RetrievalProjectionDeadLetterFinalizer(
                        new ObjectMapper(), sourceRetrievalProjectionFinalizer, configured.retrievalProjection());
    }

    /** 批次守卫可选：没有向量批次仓储时仍然完成快照与任务收尾。 */
    @Autowired(required = false)
    void setEmbeddingBatches(SourceEmbeddingBatchRepository embeddingBatches) {
        if (retrievalProjectionFinalizer != null) {
            retrievalProjectionFinalizer.withEmbeddingBatches(embeddingBatches);
        }
    }

    @Scheduled(fixedDelayString = "${noteweave.kafka.outbox-redrive-delay-ms:1000}")
    public void dispatchReadyMessages() {
        if (!enabled) {
            return;
        }
        outboxDispatcher.dispatchTaskMessages(
                new DurableOutboxDispatcher.TopicPolicy(
                        topics,
                        OutboxDispatchPolicy.LEASE_DURATION,
                        DurableOutboxDispatcher.CompletionMode.SENT,
                        50
                ),
                50,
                message -> kafkaTemplate.send(new ProducerRecord<>(
                        message.topic(), message.messageKey(), message.payloadJson()
                )).get(10, TimeUnit.SECONDS),
                message -> handleDeliveryExhausted(message)
        );
    }

    /**
     * 投递耗尽后的收尾。检索投影（向量化、索引、同步投影）与解析/切片一样要做业务收尾，
     * 否则快照会一直停在 PROCESSING 且向量批次租约不释放。收尾异常只记日志，不能打断投递循环。
     */
    private void handleDeliveryExhausted(DurableOutboxDispatcher.Message message) {
        try {
            finalizeDeliveryExhausted(message);
        } catch (RuntimeException failure) {
            // 收尾异常不能打断投递循环，消息已经进入死信，可以重驱
            log.warn("Outbox 投递耗尽收尾失败: outboxId={}, topic={}", message.outboxId(), message.topic(), failure);
        }
    }

    private void finalizeDeliveryExhausted(DurableOutboxDispatcher.Message message) {
        if (message.taskId() == null) {
            return;
        }
        if (sourceParseFailureFinalizer != null && (sourceParseTopic.equals(message.topic())
                || com.noteweave.source.SourcePipelineStages.TOPIC_CHUNK.equals(message.topic()))) {
            sourceParseFailureFinalizer.finalizeDeliveryExhausted(message.taskId(), message.payloadJson());
            return;
        }
        if (retrievalProjectionFinalizer != null && retrievalProjectionFinalizer.handles(message.topic())
                && retrievalProjectionFinalizer.finalizeFailure(message.topic(), message.payloadJson(),
                        RetrievalProjectionDeadLetterFinalizer.DELIVERY_EXHAUSTED_ERROR_CODE)) {
            return;
        }
        // 收尾没有生效（载荷不可用、批次已过时等）时仍然把任务打失败，任务不能停在执行中
        taskService.failTask(message.taskId(), "OUTBOX_DEAD_LETTER",
                "Kafka Outbox 投递耗尽，已进入死信",
                "OUTBOX_DISPATCH_EXHAUSTED", true);
    }

    public TaskOutboxMetricsResponse metrics() {
        return new TaskOutboxMetricsResponse(count("READY"), count("PROCESSING"), count("SENT"), count("DEAD_LETTER"));
    }

    public List<TaskOutboxDeadLetterResponse> listDeadLetters(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbcTemplate.query("""
                select id, task_id, topic, message_key, attempt_count, coalesce(last_error, '') last_error, dead_lettered_at
                from task_outbox where status = 'DEAD_LETTER' and topic in (%s)
                order by dead_lettered_at desc, created_at desc limit ?
                """.formatted(topicPlaceholders()), (rs, rowNum) -> new TaskOutboxDeadLetterResponse(
                rs.getString("id"), rs.getString("task_id"), rs.getString("topic"), rs.getString("message_key"),
                rs.getInt("attempt_count"), rs.getString("last_error"),
                rs.getTimestamp("dead_lettered_at") == null ? Instant.EPOCH : rs.getTimestamp("dead_lettered_at").toInstant()
        ), topicArguments(safeLimit));
    }

    @Transactional
    public void redrive(String outboxId) {
        List<OutboxRow> rows = jdbcTemplate.query("""
                select id, task_id, topic, message_key, payload_json, attempt_count
                from task_outbox where id = ? and status = 'DEAD_LETTER'
                """, (rs, rowNum) -> new OutboxRow(rs.getString("id"), rs.getString("task_id"), rs.getString("topic"),
                rs.getString("message_key"), rs.getString("payload_json"), rs.getInt("attempt_count")), outboxId);
        if (rows.isEmpty() || !topics.contains(rows.get(0).topic())) {
            throw new BusinessException("TASK_OUTBOX_DEAD_LETTER_NOT_FOUND", "Outbox 死信不存在或不可重驱");
        }
        OutboxRow row = rows.get(0);
        jdbcTemplate.update("""
                update task_outbox set status = 'READY', attempt_count = 0, last_error = null,
                    claimed_at = null, lease_owner = null, lease_until = null,
                    next_attempt_at = current_timestamp, dead_lettered_at = null
                where id = ? and status = 'DEAD_LETTER'
                """, outboxId);
        if (row.taskId() != null) {
            taskService.redriveTask(row.taskId(), false);
        }
    }

    private int count(String status) {
        Integer value = jdbcTemplate.queryForObject("""
                select count(*) from task_outbox where status = ? and topic in (%s)
                """.formatted(topicPlaceholders()), Integer.class, statusAndTopics(status));
        return value == null ? 0 : value;
    }

    private String topicPlaceholders() {
        return String.join(", ", java.util.Collections.nCopies(topics.size(), "?"));
    }

    private Object[] topicArguments(int limit) {
        java.util.ArrayList<Object> arguments = new java.util.ArrayList<>(topics);
        arguments.add(limit);
        return arguments.toArray();
    }

    private Object[] statusAndTopics(String status) {
        java.util.ArrayList<Object> arguments = new java.util.ArrayList<>();
        arguments.add(status);
        arguments.addAll(topics);
        return arguments.toArray();
    }

    private record OutboxRow(
            String id,
            String taskId,
            String topic,
            String messageKey,
            String payloadJson,
            int attemptCount
    ) { }
}
