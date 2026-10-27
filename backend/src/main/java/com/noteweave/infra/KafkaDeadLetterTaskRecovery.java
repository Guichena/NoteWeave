package com.noteweave.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.FinalizationOutcome;
import com.noteweave.infra.outbox.OutboxFinalizationExecutor;
import com.noteweave.infra.outbox.OutboxFinalizationRepository;
import com.noteweave.retrieval.projection.RetrievalProjectionDeadLetterFinalizer;
import com.noteweave.retrieval.projection.SourceRetrievalProjectionFinalizer;
import com.noteweave.retrieval.provider.RetrievalProviderException;
import com.noteweave.source.SourceParseFailureFinalizer;
import com.noteweave.source.SourceTopicNames;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 消费者重试耗尽后的业务收尾：消息已经发往 DLT，这里把对应的资料和任务标记为终态，
 * 避免资料一直停在处理中。
 * <p>
 * 消费死信拥有独立的恢复身份（B-01）：topic/partition/offset/consumerGroup 构成
 * 补偿去重范围，与 producer 侧的 outbox id 区分，不能冒用某个 producer outbox ID。
 * 收尾动作先在持久补偿表幂等登记再内联执行：内联失败或进程重启后，
 * 补偿执行器仍能按登记身份发现并重试，收尾失败不会随重启消失。
 */
@Component
public class KafkaDeadLetterTaskRecovery {
    private static final Logger log = LoggerFactory.getLogger(KafkaDeadLetterTaskRecovery.class);

    private final ObjectMapper objectMapper;
    private final RetrievalProjectionDeadLetterFinalizer retrievalProjectionFinalizer;
    private final SourceParseFailureFinalizer sourceParseFailureFinalizer;
    private final SourceTopicNames topicNames;
    private final String consumerGroup;
    private final String memoryExtractionTopic;
    private OutboxFinalizationRepository finalizations;
    private com.noteweave.memory.MemoryProcessingReceiptService memoryReceipts;

    @org.springframework.beans.factory.annotation.Autowired
    public KafkaDeadLetterTaskRecovery(
            ObjectMapper objectMapper,
            SourceRetrievalProjectionFinalizer projectionFinalizer,
            SourceParseFailureFinalizer sourceParseFailureFinalizer,
            SourceTopicNames topicNames,
            @Value("${spring.kafka.consumer.group-id:noteweave}") String consumerGroup,
            @Value("${noteweave.kafka.topics.memory-extraction:noteweave.memory.extraction}") String memoryExtractionTopic
    ) {
        this(objectMapper, projectionFinalizer, sourceParseFailureFinalizer, topicNames,
                consumerGroup, memoryExtractionTopic, null);
    }

    /** 兼容旧测试构造：无补偿仓储。 */
    public KafkaDeadLetterTaskRecovery(
            ObjectMapper objectMapper,
            SourceRetrievalProjectionFinalizer projectionFinalizer,
            SourceParseFailureFinalizer sourceParseFailureFinalizer,
            SourceTopicNames topicNames
    ) {
        this(objectMapper, projectionFinalizer, sourceParseFailureFinalizer, topicNames,
                "noteweave", "noteweave.memory.extraction");
    }

    private KafkaDeadLetterTaskRecovery(
            ObjectMapper objectMapper,
            SourceRetrievalProjectionFinalizer projectionFinalizer,
            SourceParseFailureFinalizer sourceParseFailureFinalizer,
            SourceTopicNames topicNames,
            String consumerGroup,
            String memoryExtractionTopic,
            Void unused
    ) {
        this.objectMapper = objectMapper;
        // 检索投影的死信收尾与 outbox 投递耗尽共用同一套逻辑
        this.retrievalProjectionFinalizer = new RetrievalProjectionDeadLetterFinalizer(
                objectMapper, projectionFinalizer, topicNames);
        this.sourceParseFailureFinalizer = sourceParseFailureFinalizer;
        this.topicNames = topicNames;
        this.consumerGroup = consumerGroup;
        this.memoryExtractionTopic = memoryExtractionTopic;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEmbeddingBatches(com.noteweave.retrieval.projection.SourceEmbeddingBatchRepository embeddingBatches) {
        retrievalProjectionFinalizer.withEmbeddingBatches(embeddingBatches);
    }

    /** 补偿登记仓储：注入后消费死信收尾纳入持久补偿（B-01）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setFinalizations(OutboxFinalizationRepository finalizations) {
        this.finalizations = finalizations;
    }

    /** MEM-01：记忆抽取死信收尾依赖回执服务；测试构造可以不装配，此时该主题按非流水线主题跳过。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMemoryReceipts(com.noteweave.memory.MemoryProcessingReceiptService memoryReceipts) {
        this.memoryReceipts = memoryReceipts;
    }

    public void recover(ConsumerRecord<?, ?> record, Exception exception) {
        // 消费身份：与 producer 侧 outbox id 完全不同的命名空间
        String recoveryId = record.topic() + ":" + record.partition() + ":" + record.offset() + ":" + consumerGroup;
        Map<String, Object> payload = readPayload(record);
        String kind = finalizationKind(record);
        if (kind == null) {
            // 非流水线主题没有可收尾的业务对象
            return;
        }
        if (finalizations == null) {
            // 兼容未装配补偿仓储的测试构造：只做内联收尾
            runInline(record, exception, payload);
            return;
        }
        String existing = finalizations.statusOf(OutboxFinalizationRepository.STORE_KAFKA_DLT, recoveryId, 1);
        if (OutboxFinalizationRepository.STATUS_APPLIED.equals(existing)
                || OutboxFinalizationRepository.STATUS_IGNORED_STALE.equals(existing)) {
            return;
        }
        Map<String, Object> identity = buildIdentity(record, exception, payload);
        finalizations.insertPending(
                OutboxFinalizationRepository.STORE_KAFKA_DLT, recoveryId, 1, kind,
                firstText(identity, "workspaceId", "workspace_id"), identity, 5);
        FinalizationOutcome outcome = runInline(record, exception, payload);
        persistOutcome(recoveryId, kind, outcome, exception);
    }

    /** 内联执行原有收尾逻辑；结果带回语义，供补偿登记写回。 */
    private FinalizationOutcome runInline(ConsumerRecord<?, ?> record, Exception exception, Map<String, Object> payload) {
        // 解析和切片阶段失败时资料停在解析中；向量化和索引阶段失败时资料停在索引中
        // 主题名取自配置：硬编码字面量或常量一旦与配置不一致，死信收尾就会漏掉这条记录
        boolean chunkStage = topicNames.chunk().equals(record.topic());
        if (topicNames.isParseStage(record.topic())) {
            return recoverSourceParse(record, exception, payload, chunkStage);
        }
        if (retrievalProjectionFinalizer.handles(record.topic())) {
            return retrievalProjectionFinalizer.finalizeFailureIdentity(
                    record.topic(), payload == null ? Map.of() : payload, errorCode(exception));
        }
        if (memoryExtractionTopic.equals(record.topic())) {
            return recoverMemoryExtraction(payload, exception);
        }
        return FinalizationOutcome.IGNORED_STALE;
    }

    /**
     * MEM-01：记忆抽取的死信必须把处理回执推进到 RETRY_WAIT/TERMINAL_FAILURE 并收尾任务。
     * 旧实现只登记了 MEMORY_EXTRACTION 补偿类型却没有对应分支，回执停在 PENDING、任务停在 RUNNING，
     * 补偿行还被写成 IGNORED_STALE，看起来像已经处理过。
     */
    private FinalizationOutcome recoverMemoryExtraction(Map<String, Object> payload, Exception exception) {
        if (memoryReceipts == null || payload == null) {
            return FinalizationOutcome.IGNORED_STALE;
        }
        String workspaceId = firstText(payload, "workspace_id", "workspaceId");
        String messageId = firstText(payload, "message_id", "messageId");
        if (workspaceId == null || messageId == null) {
            log.warn("记忆抽取死信缺少回执身份，无法收尾: topic={}, keys={}",
                    memoryExtractionTopic, payload.keySet());
            return FinalizationOutcome.IGNORED_STALE;
        }
        try {
            return memoryReceipts.finalizeDeadLettered(workspaceId, messageId, memoryErrorCode(exception));
        } catch (RuntimeException failure) {
            // 收尾本身暂时失败（数据库不可用等）：不抛出，避免死信被反复重投；交给补偿执行器重试
            log.warn("记忆抽取死信收尾暂时失败: workspace={}, message={}, reason={}",
                    workspaceId, messageId, failure.getMessage());
            return FinalizationOutcome.RETRYABLE_FAILURE;
        }
    }

    /** 记忆抽取的死信错误码不能复用检索投影的默认值，否则回执里的原因会指向错误的业务。 */
    private static String memoryErrorCode(Throwable failure) {
        BusinessException businessFailure = findCause(failure, BusinessException.class);
        String code = businessFailure == null ? null : businessFailure.code();
        return code == null || code.isBlank() ? "MEMORY_EXTRACTION_FAILED" : code;
    }
    /** 载荷键名同时兼容驼峰与下划线：记忆抽取等主题由 Python 侧或旧版本写入 snake_case。 */
    private static String firstText(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            Object value = payload.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    /** 把内联结果写回补偿登记：成功即终态，暂时失败留给补偿执行器重试。 */
    private void persistOutcome(String recoveryId, String kind, FinalizationOutcome outcome, Exception exception) {
        switch (outcome) {
            case APPLIED -> finalizations.completePending(
                    OutboxFinalizationRepository.STORE_KAFKA_DLT, recoveryId, 1,
                    OutboxFinalizationRepository.STATUS_APPLIED, null);
            case IGNORED_STALE -> {
                // MEM-01：只有「业务对象确实已过期、收尾无副作用」才写 IGNORED_STALE。
                // 登记补偿的类型必须在 runInline 里真的执行过收尾，否则这里会把悬挂的任务
                // 记成已处理；暂时失败走下面的 retryPending，不能落到这个分支。
                log.info("Kafka DLT 收尾判定为过期，无副作用: recoveryId={}, kind={}", recoveryId, kind);
                finalizations.completePending(
                        OutboxFinalizationRepository.STORE_KAFKA_DLT, recoveryId, 1,
                        OutboxFinalizationRepository.STATUS_IGNORED_STALE, null);
            }
            default -> {
                log.warn("Kafka DLT 收尾暂时失败，已登记补偿等待重试: recoveryId={}, kind={}", recoveryId, kind);
                finalizations.retryPending(
                        OutboxFinalizationRepository.STORE_KAFKA_DLT, recoveryId, 1,
                        errorCode(exception), "消费死信收尾暂时失败: kind=" + kind, 60);
            }
        }
    }

    private String finalizationKind(ConsumerRecord<?, ?> record) {
        if (topicNames.isParseStage(record.topic())) {
            return OutboxFinalizationExecutor.KIND_KAFKA_DLT_PARSE;
        }
        if (retrievalProjectionFinalizer.handles(record.topic())) {
            return OutboxFinalizationExecutor.KIND_KAFKA_DLT_PROJECTION;
        }
        if (memoryExtractionTopic.equals(record.topic()) && memoryReceipts != null) {
            // 没有回执服务时按非流水线主题处理：不登记补偿，避免把无法执行的行写成 IGNORED_STALE
            return OutboxFinalizationExecutor.KIND_MEMORY_EXTRACTION;
        }
        return null;
    }

    /** 从记录值提取冻结身份：只保留收尾需要的身份字段与错误码，不复制全文载荷。 */
    private Map<String, Object> buildIdentity(
            ConsumerRecord<?, ?> record, Exception exception, Map<String, Object> payload) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("topic", record.topic());
        identity.put("errorCode", errorCode(exception));
        if (topicNames.isParseStage(record.topic())) {
            BusinessException businessFailure = findCause(exception, BusinessException.class);
            boolean chunkStage = topicNames.chunk().equals(record.topic());
            identity.put("retryable", retryableParseFailure(exception, businessFailure));
            identity.put("errorMessage", businessFailure != null ? businessFailure.getMessage()
                    : chunkStage ? "资料切片过程中出错" : "资料解析过程中出错");
        }
        if (payload != null) {
            for (String key : new String[]{"taskId", "workspaceId", "sourceId", "snapshotId",
                    "sourceSnapshotId", "batchNo", "batchVersion",
                    // memory 抽取载荷使用 snake_case 键
                    "task_id", "workspace_id", "message_id"}) {
                if (payload.containsKey(key) && payload.get(key) != null) {
                    identity.put(key, payload.get(key));
                }
            }
        }
        return identity;
    }

    /**
     * PARSE-01：死信收尾是否标记为可重试，不再按「是不是 BusinessException」一刀切。
     * 解析客户端已按状态码分类（429/503/504 可重试、415/422 终态），优先采信它的判定；
     * 其它业务异常仍视为终态，非业务异常（存储读取失败等）视为可重试。
     */
    private boolean retryableParseFailure(Throwable exception, BusinessException businessFailure) {
        com.noteweave.source.SourceParserUnavailableException parserFailure =
                findCause(exception, com.noteweave.source.SourceParserUnavailableException.class);
        return parserFailure != null ? parserFailure.retryable() : businessFailure == null;
    }

    private FinalizationOutcome recoverSourceParse(
            ConsumerRecord<?, ?> record, Exception exception, Map<String, Object> payload, boolean chunkStage) {
        if (payload == null) {
            log.warn("Source parse DLT payload cannot be parsed: topic={}, partition={}, offset={}",
                    record.topic(), record.partition(), record.offset());
            return FinalizationOutcome.IGNORED_STALE;
        }
        String taskId = text(payload.get("taskId"));
        String workspaceId = text(payload.get("workspaceId"));
        String sourceId = text(payload.get("sourceId"));
        String snapshotId = text(payload.get("snapshotId"));
        if (isBlank(taskId) || isBlank(workspaceId) || isBlank(sourceId) || isBlank(snapshotId)) {
            log.warn("Source parse DLT payload is missing identity: topic={}, partition={}, offset={}",
                    record.topic(), record.partition(), record.offset());
            return FinalizationOutcome.IGNORED_STALE;
        }
        // 文档本身无法解析（类型不支持、PDF 无文本等）属于业务错误，重试没有意义；
        // 其余异常（例如读取对象存储失败）可能是暂时性的，标记为可重试。
        // 切片阶段有独立的错误码和文案，避免把切片失败展示成解析失败。
        BusinessException businessFailure = findCause(exception, BusinessException.class);
        String defaultErrorCode = chunkStage ? "SOURCE_CHUNK_FAILED" : "SOURCE_PARSE_FAILED";
        String defaultErrorMessage = chunkStage ? "资料切片过程中出错" : "资料解析过程中出错";
        String errorCode = businessFailure != null ? businessFailure.code() : defaultErrorCode;
        String errorMessage = businessFailure != null ? businessFailure.getMessage() : defaultErrorMessage;
        try {
            Integer expectedGeneration = taskGeneration(payload);
            if (expectedGeneration == null) {
                // 旧消息没有代次字段：保持原有收尾行为（任务已终态时仍按过期跳过）
                return sourceParseFailureFinalizer.finalizeProcessingFailed(
                        taskId, workspaceId, sourceId, snapshotId, errorCode, errorMessage,
                        retryableParseFailure(exception, businessFailure));
            }
            return sourceParseFailureFinalizer.finalizeProcessingFailed(
                    taskId, workspaceId, sourceId, snapshotId, errorCode, errorMessage,
                    retryableParseFailure(exception, businessFailure), null, expectedGeneration);
        } catch (RuntimeException finalizeFailure) {
            log.warn("Source parse DLT finalization failed: taskId={}, sourceId={}, reason={}",
                    taskId, sourceId, finalizeFailure.getMessage(), finalizeFailure);
            return FinalizationOutcome.RETRYABLE_FAILURE;
        }
    }

    /** SRC-04：负载里的任务代次。旧消息没有该字段时返回 null，按不校验处理（向后兼容）。 */
    private Integer taskGeneration(Map<String, Object> payload) {
        String value = text(payload.get("taskGeneration"));
        if (isBlank(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
    private Map<String, Object> readPayload(ConsumerRecord<?, ?> record) {
        try {
            return objectMapper.readValue(String.valueOf(record.value()), new TypeReference<>() {
            });
        } catch (Exception parseFailure) {
            return null;
        }
    }

    private String errorCode(Throwable failure) {
        RetrievalProviderException providerException = findCause(failure, RetrievalProviderException.class);
        return providerException != null ? providerException.errorCode() : "RETRIEVAL_PROJECTION_FAILED";
    }

    private static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
