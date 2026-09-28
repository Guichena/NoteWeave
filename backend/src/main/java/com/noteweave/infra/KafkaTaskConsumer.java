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

    @KafkaListener(topics = "${noteweave.kafka.topics.source-parse}",
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

    @KafkaListener(topics = "${noteweave.kafka.topics.retrieval-projection}",
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

    @KafkaListener(topics = "${noteweave.kafka.topics.wiki-ingest}",
                   groupId = "${spring.kafka.consumer.group-id}",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onWikiIngest(ConsumerRecord<String, String> record) {
        handle("wiki.ingest", record, payload -> {
            String taskId = (String) payload.get("taskId");
            String sourceId = (String) payload.get("sourceId");
            String workspaceId = (String) payload.get("workspaceId");
            if (sourceId != null && workspaceId != null && taskId != null) {
                wikiIngestService.runSourceIngestNow(taskId, workspaceId, sourceId);
            }
        });
    }

    @KafkaListener(topics = "${noteweave.kafka.topics.wiki-retract}",
                   groupId = "${spring.kafka.consumer.group-id}",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onWikiRetract(ConsumerRecord<String, String> record) {
        handle("wiki.retract", record, payload -> {
            String taskId = (String) payload.get("taskId");
            String sourceId = (String) payload.get("sourceId");
            String workspaceId = (String) payload.get("workspaceId");
            if (sourceId != null && workspaceId != null && taskId != null) {
                wikiIngestService.runSourceRetractNow(taskId, workspaceId, sourceId);
            }
        });
    }

    @KafkaListener(topics = "${noteweave.kafka.topics.conversation-summary}",
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
            String summary = messages.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .map(message -> ConversationTopicSummaryV2Service.summarizeMessage(
                            text(message.get("role")), text(message.get("content"))))
                    .filter(value -> !value.isBlank())
                    .collect(java.util.stream.Collectors.joining("\n"));
            if (summary.isBlank()) {
                throw new IllegalArgumentException("conversation.summary payload contains no summary content");
            }
            if (v2) {
                if (topicSummaryV2Service == null) {
                    throw new IllegalStateException("topic summary v2 service is unavailable");
                }
                if (summary.length() > 16_000) summary = summary.substring(0, 16_000);
                topicSummaryV2Service.promote(segmentId, revisionId,
                        new PromoteSegmentSummaryRequest(summary, sha256(summary)));
            } else {
                if (segmentSummaryPromotionService == null) {
                    throw new IllegalStateException("conversation summary promotion service is unavailable");
                }
                segmentSummaryPromotionService.promote(segmentId, revisionId,
                        new PromoteSegmentSummaryRequest(summary, sha256(summary)));
            }
        });
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
            throw new IllegalArgumentException("source.parse payload missing " + field);
        }
    }

    private boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
