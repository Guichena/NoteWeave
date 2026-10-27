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
import com.noteweave.infra.outbox.OutboxFinalizationExecutor;
import com.noteweave.infra.outbox.OutboxFinalizationRepository;
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
    private OutboxFinalizationRepository finalizations;

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

    /** 补偿登记仓储：注入后死信处理从内联收尾切换为持久补偿（B-01）。 */
    @Autowired(required = false)
    void setFinalizations(OutboxFinalizationRepository finalizations) {
        this.finalizations = finalizations;
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
     * 投递耗尽后的补偿登记（B-01）：在 DEAD_LETTER 终止写入的同一事务内插入 PENDING 补偿，
     * 不在这里调用业务 finalizer。业务收尾（解析失败、检索投影失败、任务兜底）由
     * OutboxFinalizationExecutor 认领租约后异步执行；finalizer 抛异常只会让补偿进入
     * RETRY_WAIT，死信事实保持已提交。登记异常则整个死信事务回滚，杜绝「死信已提交
     * 但没有补偿」的窗口，消息保持原状态等待下一次扫描。
     */
    private void handleDeliveryExhausted(DurableOutboxDispatcher.Message message) {
        if (finalizations == null) {
            // 兼容未装配补偿仓储的测试构造：退回旧的内联收尾行为
            try {
                if (message.taskId() != null) {
                    if (sourceParseFailureFinalizer != null && topicNames.isParseStage(message.topic())) {
                        sourceParseFailureFinalizer.finalizeDeliveryExhausted(
                                message.taskId(), message.payloadJson());
                    } else if (retrievalProjectionFinalizer != null && retrievalProjectionFinalizer.handles(message.topic())) {
                        com.noteweave.common.FinalizationOutcome outcome =
                                retrievalProjectionFinalizer.finalizeFailure(message.topic(), message.payloadJson(),
                                        RetrievalProjectionDeadLetterFinalizer.DELIVERY_EXHAUSTED_ERROR_CODE);
                        if (!outcome.success()) {
                            taskService.failTask(message.taskId(), "OUTBOX_DEAD_LETTER",
                                    "Kafka Outbox 投递耗尽，已进入死信",
                                    "OUTBOX_DISPATCH_EXHAUSTED", true);
                        }
                    } else {
                        taskService.failTask(message.taskId(), "OUTBOX_DEAD_LETTER",
                                "Kafka Outbox 投递耗尽，已进入死信",
                                "OUTBOX_DISPATCH_EXHAUSTED", true);
                    }
                }
            } catch (RuntimeException failure) {
                log.warn("Outbox 投递耗尽收尾失败: outboxId={}, topic={}",
                        message.outboxId(), message.topic(), failure);
            }
            return;
        }
        Map<String, Object> identity = readIdentity(message.payloadJson());
        identity.put("topic", message.topic());
        String kind = finalizationKind(message.topic(), message.taskId());
        Integer generation = jdbcTemplate.queryForObject(
                "select delivery_generation from task_outbox where id = ?", Integer.class, message.outboxId());
        finalizations.insertPending(
                OutboxFinalizationRepository.STORE_TASK,
                message.outboxId(),
                generation == null ? 1 : generation,
                kind,
                text(identity.get("workspaceId")),
                identity,
                5);
    }

    /** 按主题选择补偿业务类型：解析/切片、检索投影或任务兜底。 */
    private String finalizationKind(String topic, String taskId) {
        if (sourceParseFailureFinalizer != null && topicNames.isParseStage(topic)) {
            return OutboxFinalizationExecutor.KIND_SOURCE_PARSE;
        }
        if (retrievalProjectionFinalizer != null && retrievalProjectionFinalizer.handles(topic)) {
            return OutboxFinalizationExecutor.KIND_RETRIEVAL_PROJECTION;
        }
        return OutboxFinalizationExecutor.KIND_TASK_FALLBACK;
    }

    /** 从死信载荷提取冻结业务身份：只保留身份字段，不把全文载荷写入补偿表。 */
    private Map<String, Object> readIdentity(String payloadJson) {
        Map<String, Object> identity = new java.util.LinkedHashMap<>();
        try {
            Map<String, Object> payload = PAYLOAD_MAPPER.readValue(
                    payloadJson, new TypeReference<Map<String, Object>>() { });
            for (String key : new String[]{"taskId", "workspaceId", "workspace_id", "sourceId", "source_id",
                    "snapshotId", "sourceSnapshotId", "messageId", "message_id", "batchNo", "batchVersion"}) {
                if (payload.containsKey(key) && payload.get(key) != null) {
                    identity.put(key, payload.get(key));
                }
            }
        } catch (Exception invalidPayload) {
            log.warn("Outbox 死信载荷无法解析，补偿按空身份登记: {}", invalidPayload.getMessage());
        }
        return identity;
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
        redrive(outboxId, "INTERNAL:OUTBOX_REDRIVE", null);
    }

    /**
     * 人工重驱死信（B-03）。旧代次（旧快照、旧批次版本、旧任务、资料已删除、
     * 当前已成功）返回明确错误码并保留死信审计，不把资料打回 PROCESSING。
     * 当前代次才创建新的 deliveryGeneration：outbox 行换代重投、批次与任务恢复、
     * 重驱审计在同一短事务提交；embedding_requests_used 等累计消耗不清零，
     * 其他代次的 RUNNING 租约不触碰。
     */
    @Transactional
    public RedriveOutcome redrive(String outboxId, String actor, String reason) {
        List<OutboxRow> rows = jdbcTemplate.query("""
                select id, task_id, topic, message_key, payload_json, attempt_count, delivery_generation
                from task_outbox where id = ? and status = 'DEAD_LETTER'
                """, (rs, rowNum) -> new OutboxRow(rs.getString("id"), rs.getString("task_id"), rs.getString("topic"),
                rs.getString("message_key"), rs.getString("payload_json"), rs.getInt("attempt_count"),
                rs.getInt("delivery_generation")), outboxId);
        if (rows.isEmpty() || !topics.contains(rows.get(0).topic())) {
            throw new BusinessException("TASK_OUTBOX_DEAD_LETTER_NOT_FOUND", "Outbox 死信不存在或不可重驱");
        }
        OutboxRow row = rows.get(0);
        // 流水线主题先核对当前业务代次与可重置状态：全部校验先于任何写操作，
        // 即使调用方绕过事务代理（单元测试直连），也不会留下半提交状态
        PipelineTarget target = pipelineTarget(row);
        if (target != null) {
            requireCurrentGeneration(target, row.topic());
            requirePipelineResettable(target, row.topic());
        }
        int newGeneration = row.deliveryGeneration() + 1;
        int moved = jdbcTemplate.update("""
                update task_outbox set status = 'READY', attempt_count = 0, last_error = null,
                    claimed_at = null, lease_owner = null, lease_until = null,
                    next_attempt_at = current_timestamp, dead_lettered_at = null,
                    delivery_generation = ?
                where id = ? and status = 'DEAD_LETTER' and delivery_generation = ?
                """, newGeneration, outboxId, row.deliveryGeneration());
        if (moved != 1) {
            throw new BusinessException("TASK_OUTBOX_REDRIVE_CONFLICT", "Outbox 死信已被其他重驱处理");
        }
        jdbcTemplate.update("""
                insert into task_outbox_redrive_audit(
                    id, outbox_id, workspace_id, actor, reason,
                    old_delivery_generation, new_delivery_generation, result, detail)
                values (?, ?, ?, ?, ?, ?, ?, 'REDRIVEN', ?)
                """, java.util.UUID.randomUUID().toString().replace("-", ""), outboxId,
                target == null ? null : target.workspaceId(), actor,
                reason == null || reason.isBlank() ? null : reason.substring(0, Math.min(500, reason.length())),
                row.deliveryGeneration(), newGeneration, row.topic());
        // FB-01：旧代次补偿与换代同提交失效——TASK_OUTBOX store 按代次精确失效，
        // KAKFA_DLT store 按同任务收敛；执行器侧围栏对迟到线程兜底，双保险。
        if (finalizations != null) {
            finalizations.cancelSuperseded(
                    OutboxFinalizationRepository.STORE_TASK, outboxId, row.deliveryGeneration(),
                    "OUTBOX_REDRIVE_SUPERSEDED");
            if (row.taskId() != null) {
                finalizations.cancelSupersededByTask(
                        OutboxFinalizationRepository.STORE_KAFKA_DLT, row.taskId(),
                        "OUTBOX_REDRIVE_SUPERSEDED");
            }
        }
        // 只复位 outbox 行和任务不够：消费者按快照阶段守卫（解析需 PENDING、切片需 CHUNKING、
        // 向量化/索引需停在对应阶段），死信收尾后资料停在 FAILED，重投的消息会被直接确认而不做任何事
        resetPipelineState(row);
        if (row.taskId() != null) {
            taskService.redriveTask(row.taskId(), false);
        }
        return new RedriveOutcome(outboxId, row.deliveryGeneration(), newGeneration);
    }

    /**
     * 系统中流重驱（FB-01 v2 补充）：流水线推进中发现的旧代次死信（如切片重跑后旧的
     * 向量化初始化消息死信）只需换新代次重投——流水线此刻正停在等待该消息的阶段，
     * 不能套用人工重驱的「快照必须处于失败态」前置校验，也不能复位任务与流水线状态。
     * 保留：来源/快照当前性校验（requireCurrentGeneration）、换代 CAS、审计写入、
     * 与本行死信事实绑定的旧代次补偿失效（TASK store 按代次精确取消；
     * KAKFA_DLT store 由其自身的 TASK_REDRIVEN 事件围栏治理，本路径不产生该事件，
     * 也不按任务盲取消可能无关的消费侧补偿）。
     */
    @Transactional
    public RedriveOutcome redriveMessage(String outboxId, String actor, String reason) {
        List<OutboxRow> rows = jdbcTemplate.query("""
                select id, task_id, topic, message_key, payload_json, attempt_count, delivery_generation
                from task_outbox where id = ? and status = 'DEAD_LETTER'
                """, (rs, rowNum) -> new OutboxRow(rs.getString("id"), rs.getString("task_id"), rs.getString("topic"),
                rs.getString("message_key"), rs.getString("payload_json"), rs.getInt("attempt_count"),
                rs.getInt("delivery_generation")), outboxId);
        if (rows.isEmpty() || !topics.contains(rows.get(0).topic())) {
            throw new BusinessException("TASK_OUTBOX_DEAD_LETTER_NOT_FOUND", "Outbox 死信不存在或不可重驱");
        }
        OutboxRow row = rows.get(0);
        PipelineTarget target = pipelineTarget(row);
        if (target != null) {
            requireCurrentGeneration(target, row.topic());
        }
        int newGeneration = row.deliveryGeneration() + 1;
        int moved = jdbcTemplate.update("""
                update task_outbox set status = 'READY', attempt_count = 0, last_error = null,
                    claimed_at = null, lease_owner = null, lease_until = null,
                    next_attempt_at = current_timestamp, dead_lettered_at = null,
                    delivery_generation = ?
                where id = ? and status = 'DEAD_LETTER' and delivery_generation = ?
                """, newGeneration, outboxId, row.deliveryGeneration());
        if (moved != 1) {
            throw new BusinessException("TASK_OUTBOX_REDRIVE_CONFLICT", "Outbox 死信已被其他重驱处理");
        }
        jdbcTemplate.update("""
                insert into task_outbox_redrive_audit(
                    id, outbox_id, workspace_id, actor, reason,
                    old_delivery_generation, new_delivery_generation, result, detail)
                values (?, ?, ?, ?, ?, ?, ?, 'REDRIVEN', 'message-redrive')
                """, java.util.UUID.randomUUID().toString().replace("-", ""), outboxId,
                target == null ? null : target.workspaceId(), actor,
                reason == null || reason.isBlank() ? null : reason.substring(0, Math.min(500, reason.length())),
                row.deliveryGeneration(), newGeneration);
        if (finalizations != null) {
            finalizations.cancelSuperseded(
                    OutboxFinalizationRepository.STORE_TASK, outboxId, row.deliveryGeneration(),
                    "OUTBOX_MESSAGE_REDRIVE_SUPERSEDED");
        }
        return new RedriveOutcome(outboxId, row.deliveryGeneration(), newGeneration);
    }

    /**
     * 统一锁序下核对重驱目标仍是当前业务代次：workspace 匹配、资料未删除、
     * 快照仍是最新版本。失败抛出带明确原因码的 BusinessException，保留死信与旧审计。
     */
    private void requireCurrentGeneration(PipelineTarget target, String topic) {
        List<int[]> mismatch = jdbcTemplate.query("""
                select s.status as source_status, ss.version_no, latest.max_version
                from source s
                join source_snapshot ss on ss.id = ? and ss.source_id = s.id
                join (select source_id, max(version_no) max_version from source_snapshot group by source_id) latest
                  on latest.source_id = s.id
                where s.workspace_id = ? and s.id = ?
                for update
                """, (rs, rowNum) -> new int[]{
                "DELETED".equals(rs.getString("source_status")) ? 1 : 0,
                rs.getInt("version_no") == rs.getInt("max_version") ? 0 : 1,
                0}, target.snapshotId(), target.workspaceId(), target.sourceId());
        if (mismatch.isEmpty()) {
            throw new BusinessException("TASK_OUTBOX_REDRIVE_TARGET_MISSING",
                    "重驱目标资料或快照不存在，已按过期处理");
        }
        if (mismatch.get(0)[0] == 1) {
            throw new BusinessException("TASK_OUTBOX_REDRIVE_SOURCE_DELETED",
                    "资料已删除，死信不再适用当前业务代次");
        }
        if (mismatch.get(0)[1] == 1) {
            throw new BusinessException("TASK_OUTBOX_REDRIVE_SNAPSHOT_STALE",
                    "资料已产生更新的快照，旧快照死信不再适用");
        }
    }

    /**
     * 重置前置校验：解析/切片要求快照处于解析失败态；向量化/索引要求
     * PARSED + 索引失败态。已成功或处理中的资料在这里被拒绝，不修改任何行。
     */
    private void requirePipelineResettable(PipelineTarget target, String topic) {
        boolean parseTopic = topicNames.parse().equals(topic) || topicNames.chunk().equals(topic);
        Integer eligible = jdbcTemplate.queryForObject("""
                select count(*) from source_snapshot
                where id = ? and source_id = ? and parse_status = 'FAILED'
                  and index_status = 'FAILED'
                """, Integer.class, target.snapshotId(), target.sourceId());
        if (!parseTopic) {
            eligible = jdbcTemplate.queryForObject("""
                    select count(*) from source_snapshot
                    where id = ? and source_id = ? and parse_status = 'PARSED'
                      and index_status = 'FAILED'
                    """, Integer.class, target.snapshotId(), target.sourceId());
        }
        if (eligible == null || eligible != 1) {
            throw new BusinessException("TASK_OUTBOX_REDRIVE_PIPELINE_STALE",
                    parseTopic ? "资料快照不在解析失败态，已成功或处理中的资料不能重驱"
                            : "资料快照不在索引失败态，已索引或处理中的资料不能重驱");
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
            // 已成功、已恢复处理中或已被新代次接管的资料不能被打回 PROCESSING（B-03）
            throw new BusinessException("TASK_OUTBOX_REDRIVE_PIPELINE_STALE",
                    "资料快照不在解析失败态，已成功或处理中的资料不能重驱");
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
     * 批次只复位当前 embedding_batch_version 的行：其他代次的 RUNNING 租约不触碰，
     * COMPLETED 批次不重算，embedding_requests_used 累计消耗保留。
     */
    private void resetIndexState(PipelineTarget target, String processingStage) {
        int moved = jdbcTemplate.update("""
                update source_snapshot
                set parse_status = 'PARSED', index_status = 'INDEXING', processing_stage = ?,
                    index_attempt_count = 0, next_index_retry_at = null
                where id = ? and source_id = ? and parse_status = 'PARSED' and index_status = 'FAILED'
                """, processingStage, target.snapshotId(), target.sourceId());
        if (moved != 1) {
            throw new BusinessException("TASK_OUTBOX_REDRIVE_PIPELINE_STALE",
                    "资料快照不在索引失败态，已索引或处理中的资料不能重驱");
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
                  and batch_version = (
                      select embedding_batch_version from source_snapshot where id = ?
                  )
                """, target.snapshotId(), target.snapshotId());
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
            int attemptCount,
            int deliveryGeneration
    ) { }

    private record PipelineTarget(String workspaceId, String sourceId, String snapshotId) { }

    /** 人工重驱结果：旧/新投递代次一并返回，供审计与前端展示。 */
    public record RedriveOutcome(String outboxId, int oldDeliveryGeneration, int newDeliveryGeneration) { }
}
