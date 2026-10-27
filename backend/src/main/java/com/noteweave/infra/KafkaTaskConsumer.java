package com.noteweave.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.knowledge.WikiIngestService;
import com.noteweave.retrieval.projection.SourceRetrievalProjectionCoordinator;
import com.noteweave.source.SourceParseService;
import com.noteweave.source.SourceParseService.SourceParseAssessment;
import com.noteweave.source.SourceParseService.SourceParseDisposition;
import com.noteweave.task.TaskService;
import com.noteweave.conversation.PromoteSegmentSummaryRequest;
import com.noteweave.conversation.SegmentSummaryPromotionService;
import com.noteweave.conversation.ConversationTopicSummaryV2Service;
import com.noteweave.conversation.ConversationSummaryGenerator;
import com.noteweave.common.RequestContext;
import java.util.Map;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Kafka 异步任务消费者。
 * <p>
 * 监听有真实处理器的 source parse / retrieval projection / wiki / conversation summary topic，
 * 把 outbox 表里 READY 状态的 message 真正消费掉。
 */
@Component
@ConditionalOnProperty(name = "noteweave.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaTaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaTaskConsumer.class);

    private final SourceParseService sourceParseService;
    private final WikiIngestService wikiIngestService;
    private final TaskService taskService;
    private final ObjectMapper objectMapper;
    private final SourceRetrievalProjectionCoordinator projectionCoordinator;
    private final SegmentSummaryPromotionService segmentSummaryPromotionService;
    private final ConversationTopicSummaryV2Service topicSummaryV2Service;
    private ConversationSummaryGenerator summaryGenerator = ConversationSummaryGenerator.extractiveOnly();
    private com.noteweave.memory.MemoryConversationExtractionService memoryExtractionService;
    private com.noteweave.memory.MemoryArtifactEditService artifactEditService;

    public KafkaTaskConsumer(SourceParseService sourceParseService,
                             WikiIngestService wikiIngestService,
                             TaskService taskService,
                             ObjectMapper objectMapper) {
        this(sourceParseService, wikiIngestService, taskService, objectMapper, null, null, null);
    }

    public KafkaTaskConsumer(SourceParseService sourceParseService,
                             WikiIngestService wikiIngestService,
                             TaskService taskService,
                             ObjectMapper objectMapper,
                             SourceRetrievalProjectionCoordinator projectionCoordinator) {
        this(sourceParseService, wikiIngestService, taskService, objectMapper, projectionCoordinator, null, null);
    }

    public KafkaTaskConsumer(SourceParseService sourceParseService,
                             WikiIngestService wikiIngestService,
                             TaskService taskService,
                             ObjectMapper objectMapper,
                             SourceRetrievalProjectionCoordinator projectionCoordinator,
                             SegmentSummaryPromotionService segmentSummaryPromotionService) {
        this(sourceParseService, wikiIngestService, taskService, objectMapper, projectionCoordinator,
                segmentSummaryPromotionService, null);
    }

    @Autowired
    public KafkaTaskConsumer(SourceParseService sourceParseService,
                             WikiIngestService wikiIngestService,
                             TaskService taskService,
                             ObjectMapper objectMapper,
                             SourceRetrievalProjectionCoordinator projectionCoordinator,
                             SegmentSummaryPromotionService segmentSummaryPromotionService,
                             ConversationTopicSummaryV2Service topicSummaryV2Service) {
        this.sourceParseService = sourceParseService;
        this.wikiIngestService = wikiIngestService;
        this.taskService = taskService;
        this.objectMapper = objectMapper;
        this.projectionCoordinator = projectionCoordinator;
        this.segmentSummaryPromotionService = segmentSummaryPromotionService;
        this.topicSummaryV2Service = topicSummaryV2Service;
    }

    @Autowired(required = false)
    void setSummaryGenerator(ConversationSummaryGenerator summaryGenerator) {
        if (summaryGenerator != null) this.summaryGenerator = summaryGenerator;
    }

    @Autowired(required = false)
    void setMemoryExtractionService(com.noteweave.memory.MemoryConversationExtractionService service) {
        this.memoryExtractionService = service;
    }

    @Autowired(required = false)
    void setArtifactEditService(com.noteweave.memory.MemoryArtifactEditService service) {
        this.artifactEditService = service;
    }

    @KafkaListener(id = "source-parse", topics = "${noteweave.kafka.topics.source-parse}",
                   groupId = "${spring.kafka.consumer.group-id}",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onSourceParse(ConsumerRecord<String, String> record) {
        handle("source.parse", record, payload -> {
            String taskId = (String) payload.get("taskId");
            String workspaceId = (String) payload.get("workspaceId");
            String sourceId = (String) payload.get("sourceId");
            String snapshotId = (String) payload.get("snapshotId");
            requirePayloadValue(taskId, "taskId");
            requirePayloadValue(workspaceId, "workspaceId");
            requirePayloadValue(sourceId, "sourceId");
            requirePayloadValue(snapshotId, "snapshotId");

            SourceParseAssessment assessment = sourceParseService.assessProcessability(
                    workspaceId, sourceId, snapshotId);
            if (!assessment.processable()) {
                settleUnprocessableSourceParse(taskId, workspaceId, sourceId, snapshotId, assessment);
                return;
            }

            taskService.startTask(taskId);
            if (!sourceParseService.parseAndIndexAsyncIfProcessable(workspaceId, sourceId, snapshotId)) {
                settleUnprocessableSourceParse(
                        taskId,
                        workspaceId,
                        sourceId,
                        snapshotId,
                        sourceParseService.assessProcessability(workspaceId, sourceId, snapshotId)
                );
            }
        });
    }

    /** 切片阶段：读取解析阶段提取的文本，生成片段和阅读窗口，然后投递向量化阶段。 */
    @KafkaListener(id = "source-chunk", topics = "${noteweave.kafka.topics.source-chunk}",
                   groupId = "${spring.kafka.consumer.group-id}",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onSourceChunk(ConsumerRecord<String, String> record) {
        handle("source.chunk", record, payload -> {
            String workspaceId = text(payload.get("workspaceId"));
            String sourceId = text(payload.get("sourceId"));
            String snapshotId = text(payload.get("snapshotId"));
            String textObjectKey = text(payload.get("textObjectKey"));
            requirePayloadValue(workspaceId, "workspaceId");
            requirePayloadValue(sourceId, "sourceId");
            requirePayloadValue(snapshotId, "snapshotId");
            requirePayloadValue(textObjectKey, "textObjectKey");
            Object pageCount = payload.get("pageCount");
            sourceParseService.chunkStage(workspaceId, sourceId, snapshotId, textObjectKey,
                    text(payload.get("mimeType")), pageCount instanceof Number number ? number.intValue() : 0);
        });
    }

    /** 向量化阶段：计算片段和资料的向量，写入派生存储后投递索引阶段。 */
    @KafkaListener(id = "source-embed", topics = "${noteweave.kafka.topics.source-embed}",
                   groupId = "${spring.kafka.consumer.group-id}",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onSourceEmbed(ConsumerRecord<String, String> record) {
        handle("source.embed", record, payload -> {
            String workspaceId = text(payload.get("workspaceId"));
            String sourceId = text(payload.get("sourceId"));
            String snapshotId = text(payload.get("sourceSnapshotId"));
            requirePayloadValue(workspaceId, "workspaceId");
            requirePayloadValue(sourceId, "sourceId");
            requirePayloadValue(snapshotId, "sourceSnapshotId");
            if (projectionCoordinator == null) {
                throw new IllegalStateException("retrieval projection coordinator is unavailable");
            }
            if (payload.containsKey("batchNo")) {
                Object batch = payload.get("batchNo");
                if (!(batch instanceof Integer number) || number < 0) {
                    throw new IllegalArgumentException("向量批次编号必须为非负整数");
                }
                String version = text(payload.get("batchVersion"));
                requirePayloadValue(version, "batchVersion");
                projectionCoordinator.embedStage(workspaceId, sourceId, snapshotId,
                        text(payload.get("taskId")), number, version);
            } else {
                projectionCoordinator.embedStage(workspaceId, sourceId, snapshotId, text(payload.get("taskId")));
            }
        });
    }

    /** 索引阶段：把片段和资料写入检索索引，并完成资料的就绪收尾。 */
    @KafkaListener(id = "source-index", topics = "${noteweave.kafka.topics.source-index}",
                   groupId = "${spring.kafka.consumer.group-id}",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onSourceIndex(ConsumerRecord<String, String> record) {
        handle("source.index", record, payload -> {
            String workspaceId = text(payload.get("workspaceId"));
            String sourceId = text(payload.get("sourceId"));
            String snapshotId = text(payload.get("sourceSnapshotId"));
            requirePayloadValue(workspaceId, "workspaceId");
            requirePayloadValue(sourceId, "sourceId");
            requirePayloadValue(snapshotId, "sourceSnapshotId");
            if (projectionCoordinator == null) {
                throw new IllegalStateException("retrieval projection coordinator is unavailable");
            }
            projectionCoordinator.indexStage(workspaceId, sourceId, snapshotId, text(payload.get("taskId")),
                    text(payload.get("embeddingsObjectKey")));
        });
    }

    @KafkaListener(id = "retrieval-projection", topics = "${noteweave.kafka.topics.retrieval-projection}",
                   groupId = "${spring.kafka.consumer.group-id}",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onRetrievalProjection(ConsumerRecord<String, String> record) {
        handle("retrieval.projection", record, payload -> {
            String taskId = text(payload.get("taskId"));
            String workspaceId = text(payload.get("workspaceId"));
            String sourceId = text(payload.get("sourceId"));
            String snapshotId = text(payload.get("sourceSnapshotId"));
            requirePayloadValue(workspaceId, "workspaceId");
            requirePayloadValue(sourceId, "sourceId");
            requirePayloadValue(snapshotId, "sourceSnapshotId");
            if (projectionCoordinator == null) {
                throw new IllegalStateException("retrieval projection coordinator is unavailable");
            }
            projectionCoordinator.projectAndFinalize(workspaceId, sourceId, snapshotId, taskId);
        });
    }

    @KafkaListener(id = "wiki-ingest", topics = "${noteweave.kafka.topics.wiki-ingest}",
                   groupId = "${spring.kafka.consumer.group-id}",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onWikiIngest(ConsumerRecord<String, String> record) {
        handle("wiki.ingest", record, payload -> {
            String taskId = (String) payload.get("taskId");
            String sourceId = (String) payload.get("sourceId");
            String workspaceId = (String) payload.get("workspaceId");
            // AU-008：缺字段载荷按可重试异常外抛（重试/DLT 可见），不静默确认丢消息
            requirePayloadValue(taskId, "taskId");
            requirePayloadValue(sourceId, "sourceId");
            requirePayloadValue(workspaceId, "workspaceId");
            wikiIngestService.runSourceIngestNow(taskId, workspaceId, sourceId);
        });
    }

    @KafkaListener(id = "wiki-retract", topics = "${noteweave.kafka.topics.wiki-retract}",
                   groupId = "${spring.kafka.consumer.group-id}",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onWikiRetract(ConsumerRecord<String, String> record) {
        handle("wiki.retract", record, payload -> {
            String taskId = (String) payload.get("taskId");
            String sourceId = (String) payload.get("sourceId");
            String workspaceId = (String) payload.get("workspaceId");
            // AU-008：与 ingest 一致，缺字段不静默确认
            requirePayloadValue(taskId, "taskId");
            requirePayloadValue(sourceId, "sourceId");
            requirePayloadValue(workspaceId, "workspaceId");
            wikiIngestService.runSourceRetractNow(taskId, workspaceId, sourceId);
        });
    }

    @KafkaListener(id = "conversation-summary", topics = "${noteweave.kafka.topics.conversation-summary}",
                   groupId = "${spring.kafka.consumer.group-id}",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onConversationSummary(ConsumerRecord<String, String> record) {
        handle("conversation.summary", record, payload -> {
            boolean v2 = Integer.valueOf(2).equals(payload.get("summary_projection_version"));
            String segmentId = text(payload.get("segment_id"));
            String revisionId = text(payload.get("summary_revision_id"));
            requirePayloadValue(segmentId, "segment_id");
            requirePayloadValue(revisionId, "summary_revision_id");
            Object rawMessages = payload.get("source_messages");
            if (!(rawMessages instanceof List<?> messages) || messages.isEmpty()) {
                throw new IllegalArgumentException("conversation.summary payload missing source_messages");
            }
            // 增量摘要：payload 里带有上一版摘要时，只需要读入之后新增的消息
            List<ConversationSummaryGenerator.Turn> turns = messages.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .map(message -> new ConversationSummaryGenerator.Turn(
                            text(message.get("role")), text(message.get("content"))))
                    .toList();
            ConversationSummaryGenerator.Summary summary = summaryGenerator.summarize(
                    text(payload.get("base_summary_text")), turns);
            if (summary.text().isBlank()) {
                throw new IllegalArgumentException("conversation.summary payload contains no summary content");
            }
            PromoteSegmentSummaryRequest request = new PromoteSegmentSummaryRequest(
                    summary.text(), sha256(summary.text()), summary.method());
            if (v2) {
                if (topicSummaryV2Service == null) {
                    throw new IllegalStateException("topic summary v2 service is unavailable");
                }
                topicSummaryV2Service.promote(segmentId, revisionId, request);
            } else {
                if (segmentSummaryPromotionService == null) {
                    throw new IllegalStateException("conversation summary promotion service is unavailable");
                }
                segmentSummaryPromotionService.promote(segmentId, revisionId, request);
            }
        });
    }

    /**
     * 从对话中提取记忆候选。提取是尽力而为的后台任务：失败时把任务标记为失败，不再重试，
     * 也不影响回答本身。
     */
    @KafkaListener(id = "memory-extraction", topics = "${noteweave.kafka.topics.memory-extraction:noteweave.memory.extraction}",
                   groupId = "${spring.kafka.consumer.group-id}",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onMemoryExtraction(ConsumerRecord<String, String> record) {
        handle("memory.extraction", record, payload -> {
            String taskId = text(payload.get("task_id"));
            String workspaceId = text(payload.get("workspace_id"));
            String messageId = text(payload.get("message_id"));
            String userId = text(payload.get("user_id"));
            requirePayloadValue(workspaceId, "workspace_id");
            requirePayloadValue(messageId, "message_id");
            requirePayloadValue(userId, "user_id");
            if (memoryExtractionService == null) {
                throw new IllegalStateException("memory extraction service is unavailable");
            }
            // B-05：可重试失败已经持久登记（回执 RETRY_WAIT + 恢复扫描重投），正常返回确认消息；
            // 未持久记录的未知异常不吞掉，向外抛出走 Kafka 重试与 DLT，
            // 由消费死信补偿按回执安排有界重试，不再把所有异常都静默终态化。
            memoryExtractionService.extract(taskId, workspaceId, messageId, userId,
                    text(payload.get("content")));
        });
    }

    /**
     * B-08 / FB-03：消费 A 的 C4 v2 产物编辑事件。
     * 处理按 workspace+editEventId 幂等；两阶段回执失败独立重试。
     * C4 v2 差异合同：diffStats（可信统计）与 diffFragments（受限正文差异片段）
     * 必须随事件投递，changedSections 仅是章节标识（N-04）。
     * 同 eventId 不同载荷的 IDEMPOTENCY_CONFLICT 是可观察冲突：结构化告警后确认，
     * 保留既有回执，不让同一条消息无限重试；其余未持久记录的异常向外抛出走 Kafka 重试。
     */
    @KafkaListener(id = "artifact-edit",
                   topics = "${noteweave.kafka.topics.artifact-edit:noteweave.artifact.edit}",
                   groupId = "${spring.kafka.consumer.group-id}",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onArtifactEdit(ConsumerRecord<String, String> record) {
        handle("artifact.edit", record, payload -> {
            if (artifactEditService == null) {
                throw new IllegalStateException("artifact edit service is unavailable");
            }
            // 兼容两套 v2 字段名：A 合同 changedSectionIds/changedFragments 与早期 changedSections/diffFragments
            java.util.List<String> sections = stringList(payload, "changedSectionIds", "changedSections");
            java.util.List<String> fragments = fragmentTexts(payload, "changedFragments", "diffFragments");
            java.util.Map<String, Object> diffStats =
                    payload.get("diffStats") instanceof Map<?, ?> stats
                            ? stats.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                                    entry -> String.valueOf(entry.getKey()),
                                    entry -> java.util.Objects.requireNonNullElse(entry.getValue(), "")))
                            : java.util.Map.of();
            int schemaVersion = payload.get("schemaVersion") instanceof Number number
                    ? number.intValue() : 1;
            try {
                artifactEditService.handleArtifactEdit(
                        new com.noteweave.memory.MemoryArtifactEditService.EditEvent(
                                text(payload.get("editEventId")),
                                text(payload.get("workspaceId")),
                                text(payload.get("artifactVersionId")),
                                text(payload.get("baseVersionId")),
                                text(payload.get("originTaskId")),
                                text(payload.get("beforeDigest")),
                                text(payload.get("afterDigest")),
                                sections,
                                text(payload.get("actingUserId")),
                                schemaVersion,
                                diffStats,
                                fragments));
            } catch (com.noteweave.common.BusinessException conflict) {
                if ("IDEMPOTENCY_CONFLICT".equals(conflict.code())) {
                    // 载荷冲突保留既有回执并确认：重投同一冲突不会变好，靠告警与人工处置
                    org.slf4j.LoggerFactory.getLogger(KafkaTaskConsumer.class).error(
                            "Artifact edit event payload conflict: editEventId={}, topic={}, offset={}",
                            text(payload.get("editEventId")), record.topic(), record.offset());
                    return;
                }
                throw conflict;
            }
        });
    }

    private static java.util.List<String> stringList(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            if (payload.get(key) instanceof List<?> list) {
                return list.stream().map(String::valueOf).toList();
            }
        }
        return List.of();
    }

    /**
     * C4 v2 差异片段抽取（AU-002）：A 侧事件是 Fragment 对象列表
     * （sectionId/beforeExcerpt/afterExcerpt/…），直接 String.valueOf 会把 SHA-256
     * 摘要与截断标志等包装噪声混入偏好抽取输入。这里只取受限摘录组装为
     * 旧→新 可读文本；纯字符串元素（历史/测试路径）原样保留。
     */
    static java.util.List<String> fragmentTexts(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            if (payload.get(key) instanceof List<?> list) {
                java.util.List<String> texts = new java.util.ArrayList<>(list.size());
                for (Object element : list) {
                    if (element instanceof Map<?, ?> fragment) {
                        String section = str(fragment.get("sectionId"));
                        String before = str(fragment.get("beforeExcerpt"));
                        String after = str(fragment.get("afterExcerpt"));
                        StringBuilder line = new StringBuilder();
                        if (section != null && !section.isBlank()) {
                            line.append("[").append(section).append("] ");
                        }
                        line.append("旧: ").append(before == null ? "" : before)
                                .append(" -> 新: ").append(after == null ? "" : after);
                        texts.add(line.toString());
                    } else if (element != null) {
                        texts.add(String.valueOf(element));
                    }
                }
                return texts;
            }
        }
        return List.of();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private void handle(String topicLabel, ConsumerRecord<String, String> record, java.util.function.Consumer<Map<String, Object>> handler) {
        String eventId = record.topic() + "-" + record.partition() + "-" + record.offset();
        MDC.put(RequestContext.EVENT_ID, eventId);
        MDC.put(RequestContext.CORRELATION_ID, eventId);
        try {
            Map<String, Object> payload = objectMapper.readValue(record.value(), new TypeReference<>() {});
            putPayloadContext(payload, RequestContext.WORKSPACE_ID, "workspaceId");
            putPayloadContext(payload, RequestContext.TASK_ID, "taskId");
            putPayloadContext(payload, RequestContext.SOURCE_ID, "sourceId");
            putPayloadContext(payload, RequestContext.CORRELATION_ID, "correlationId");
            log.info("Kafka {} consumed: key={}, partition={}, offset={}", topicLabel, record.key(), record.partition(), record.offset());
            handler.accept(payload);
        } catch (Exception ex) {
            log.error("Kafka {} handler failed for key {}: {}", topicLabel, record.key(), ex.getMessage(), ex);
            throw new IllegalStateException("Kafka " + topicLabel + " handler failed", ex);
        } finally {
            MDC.clear();
        }
    }

    private void putPayloadContext(Map<String, Object> payload, String mdcKey, String payloadKey) {
        Object value = payload.get(payloadKey);
        if (value != null && !String.valueOf(value).isBlank()) {
            MDC.put(mdcKey, String.valueOf(value));
        }
    }

    private void settleUnprocessableSourceParse(
            String taskId,
            String workspaceId,
            String sourceId,
            String snapshotId,
            SourceParseAssessment assessment
    ) {
        TaskService.TaskRef task = taskService.getTaskRef(taskId);
        requireSourceParseTaskIdentity(task, workspaceId, sourceId);
        if (isTerminal(task.taskStatus())) {
            log.info("ACK duplicate source.parse for terminal task: taskId={}, status={}, sourceId={}, snapshotId={}",
                    taskId, task.taskStatus(), sourceId, snapshotId);
            return;
        }

        SourceParseDisposition disposition = assessment.disposition();
        if (disposition == SourceParseDisposition.IN_LATER_STAGE) {
            // 快照已进入切片、向量化或索引阶段，任务由后续阶段收尾，这里只确认重复消息
            log.info("ACK duplicate source.parse while later stages run: taskId={}, sourceId={}, snapshotId={}",
                    taskId, sourceId, snapshotId);
            return;
        }
        String detail = "source_status=" + assessment.sourceStatus()
                + ", snapshot_parse_status=" + assessment.snapshotParseStatus();
        if (disposition == SourceParseDisposition.TARGET_MISSING) {
            taskService.failTask(
                    taskId,
                    "SOURCE_PARSE_TARGET_MISSING",
                    "资料解析目标不存在或与消息不匹配：" + detail,
                    "SOURCE_PARSE_TARGET_MISSING",
                    false
            );
            return;
        }

        String phase = disposition == SourceParseDisposition.TARGET_DELETED
                ? "SOURCE_PARSE_TARGET_DELETED"
                : "SOURCE_PARSE_DUPLICATE";
        String message = disposition == SourceParseDisposition.TARGET_DELETED
                ? "资料或快照已删除，取消解析：" + detail
                : "资料快照已处理，确认重复消息并取消冗余任务：" + detail;
        taskService.cancelTask(taskId, phase, message, sourceId);
    }

    private void requireSourceParseTaskIdentity(TaskService.TaskRef task, String workspaceId, String sourceId) {
        if (!workspaceId.equals(task.workspaceId())
                || !"SOURCE_PARSE".equals(task.taskType())
                || !"SOURCE".equals(task.targetType())
                || !sourceId.equals(task.targetId())) {
            throw new IllegalArgumentException("source.parse task identity does not match payload");
        }
    }

    private void requirePayloadValue(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("kafka payload missing " + field);
        }
    }

    private boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
