package com.noteweave.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.common.BusinessException;
import com.noteweave.source.SourcePipelineStages;
import com.noteweave.source.SourceTopicNames;
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
import java.util.Map;
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
    private static final ObjectMapper PAYLOAD_MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final boolean enabled;
    private final List<String> topics;
    private final SourceTopicNames topicNames;
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
        this.topicNames = new SourceTopicNames(properties);
        this.topics = configured.all();
        // 向量化/索引投递耗尽要与死信收尾一致：把快照、资料和任务标记为失败，
        // 资料才能被重新处理，残留的批次租约在重新处理时被清理
        this.retrievalProjectionFinalizer = sourceRetrievalProjectionFinalizer == null ? null
                : new RetrievalProjectionDeadLetterFinalizer(
                        new ObjectMapper(), sourceRetrievalProjectionFinalizer, topicNames);
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
        if (sourceParseFailureFinalizer != null && topicNames.isParseStage(message.topic())) {
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
        // 只复位 outbox 行和任务不够：消费者按快照阶段守卫（解析需 PENDING、切片需 CHUNKING、
        // 向量化/索引需停在对应阶段），死信收尾后资料停在 FAILED，重投的消息会被直接确认而不做任何事
        resetPipelineState(row);
        if (row.taskId() != null) {
            taskService.redriveTask(row.taskId(), false);
        }
    }

    /** 按主题复位资料流水线状态；无法识别载荷或主题时保持原样，重投本身仍然生效。 */
    private void resetPipelineState(OutboxRow row) {
        boolean parseTopic = topicNames.parse().equals(row.topic());
        boolean chunkTopic = topicNames.chunk().equals(row.topic());
        boolean embedTopic = topicNames.embed().equals(row.topic());
        boolean indexTopic = topicNames.index().equals(row.topic());
        if (!parseTopic && !chunkTopic && !embedTopic && !indexTopic) {
            return;
        }
        PipelineTarget target = pipelineTarget(row);
        if (target == null) {
            return;
        }
        if (parseTopic) {
            resetParseState(target, null);
        } else if (chunkTopic) {
            resetParseState(target, SourcePipelineStages.STAGE_CHUNKING);
        } else if (embedTopic) {
            resetIndexState(target, SourcePipelineStages.STAGE_EMBEDDING);
        } else {
            resetIndexState(target, SourcePipelineStages.STAGE_INDEXING);
        }
    }

    /**
     * 解析与切片从快照 PENDING 重新开始；切片阶段还要停在 CHUNKING，切片消费者才接受消息。
     * 只在快照处于失败态（{@code parse_status='FAILED'}）时复位：旧死信被人工重驱时，
     * 已经重跑成功的资料不能被重新打回 PROCESSING。
     */
    private void resetParseState(PipelineTarget target, String processingStage) {
        int moved = jdbcTemplate.update("""
                update source_snapshot
                set parse_status = 'PENDING', index_status = 'PENDING', processing_stage = ?,
                    embedding_task_id = null, embedding_batch_version = null,
                    index_attempt_count = 0, next_index_retry_at = null
                where id = ? and source_id = ? and parse_status = 'FAILED'
                """, processingStage, target.snapshotId(), target.sourceId());
        if (moved != 1) {
            log.info("Skip source pipeline reset for redrive: snapshotId={}, stage={}",
                    target.snapshotId(), processingStage);
            return;
        }
        jdbcTemplate.update("""
                update source
                set parse_status = 'PENDING', index_status = 'PENDING', status = 'PROCESSING',
                    updated_by = 'SYSTEM:OUTBOX_REDRIVE', updated_at = current_timestamp
                where id = ? and workspace_id = ? and status <> 'DELETED'
                """, target.sourceId(), target.workspaceId());
    }

    /**
     * 向量化与索引在解析完成之后重跑，快照停在对应阶段，并清掉批次租约让批次可以被重新占用。
     * 只在索引失败态（{@code parse_status='PARSED' and index_status='FAILED'}）时复位，
     * 与 {@code SourceReprocessService.requeueIndexing} 的守卫一致：已经索引就绪的资料不能被打回 INDEXING。
     */
    private void resetIndexState(PipelineTarget target, String processingStage) {
        int moved = jdbcTemplate.update("""
                update source_snapshot
                set parse_status = 'PARSED', index_status = 'INDEXING', processing_stage = ?,
                    index_attempt_count = 0, next_index_retry_at = null
                where id = ? and source_id = ? and parse_status = 'PARSED' and index_status = 'FAILED'
                """, processingStage, target.snapshotId(), target.sourceId());
        if (moved != 1) {
            log.info("Skip source index reset for redrive: snapshotId={}, stage={}",
                    target.snapshotId(), processingStage);
            return;
        }
        jdbcTemplate.update("""
                update source
                set index_status = 'INDEXING', status = 'PROCESSING',
                    updated_by = 'SYSTEM:OUTBOX_REDRIVE', updated_at = current_timestamp
                where id = ? and workspace_id = ? and status <> 'DELETED'
                """, target.sourceId(), target.workspaceId());
        jdbcTemplate.update("""
                update source_embedding_batch
                set lease_token = null, lease_until = null,
                    status = case when status = 'COMPLETED' then status else 'READY' end,
                    updated_at = current_timestamp
                where source_snapshot_id = ?
                """, target.snapshotId());
    }

    private PipelineTarget pipelineTarget(OutboxRow row) {
        Map<String, Object> payload;
        try {
            payload = PAYLOAD_MAPPER.readValue(row.payloadJson(), new TypeReference<Map<String, Object>>() { });
        } catch (Exception exception) {
            log.warn("Outbox 重驱载荷无法解析，跳过资料状态复位: outboxId={}, topic={}", row.id(), row.topic());
            return null;
        }
        if (payload == null) {
            return null;
        }
        String workspaceId = text(payload.get("workspaceId"));
        String sourceId = text(payload.get("sourceId"));
        String snapshotId = text(payload.get("sourceSnapshotId"));
        if (snapshotId == null || snapshotId.isBlank()) {
            snapshotId = text(payload.get("snapshotId"));
        }
        if (workspaceId == null || workspaceId.isBlank() || sourceId == null || sourceId.isBlank()
                || snapshotId == null || snapshotId.isBlank()) {
            log.warn("Outbox 重驱载荷缺少资料身份，跳过资料状态复位: outboxId={}, topic={}", row.id(), row.topic());
            return null;
        }
        return new PipelineTarget(workspaceId, sourceId, snapshotId);
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
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

    private record PipelineTarget(String workspaceId, String sourceId, String snapshotId) { }
}
