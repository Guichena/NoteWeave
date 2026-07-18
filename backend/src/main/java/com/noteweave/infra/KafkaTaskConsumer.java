package com.noteweave.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.knowledge.WikiIngestService;
import com.noteweave.source.SourceParseService;
import com.noteweave.task.TaskService;
import com.noteweave.common.RequestContext;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka 异步任务消费者。
 * <p>
 * 监听 source parse / chunk / index / wiki ingest / wiki retract / generated ingest 六个 topic，
 * 把 outbox 表里 READY 状态的 message 真正消费掉。
 */
@Component
public class KafkaTaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaTaskConsumer.class);

    private final SourceParseService sourceParseService;
    private final WikiIngestService wikiIngestService;
    private final TaskService taskService;
    private final ObjectMapper objectMapper;

    public KafkaTaskConsumer(SourceParseService sourceParseService,
                             WikiIngestService wikiIngestService,
                             TaskService taskService,
                             ObjectMapper objectMapper) {
        this.sourceParseService = sourceParseService;
        this.wikiIngestService = wikiIngestService;
        this.taskService = taskService;
        this.objectMapper = objectMapper;
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
            if (workspaceId != null && sourceId != null && snapshotId != null
                    && sourceParseService.isProcessable(workspaceId, sourceId, snapshotId)) {
                taskService.startTask(taskId);
                sourceParseService.parseAndIndexAsync(workspaceId, sourceId, snapshotId);
            }
        });
    }

    @KafkaListener(topics = "${noteweave.kafka.topics.source-chunk}",
                   groupId = "${spring.kafka.consumer.group-id}",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onSourceChunk(ConsumerRecord<String, String> record) {
        handle("source.chunk", record, payload -> {
            log.debug("source.chunk event observed (current code path is in-process after parse)");
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

    @KafkaListener(topics = "${noteweave.kafka.topics.generated-ingest}",
                   groupId = "${spring.kafka.consumer.group-id}",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onGeneratedIngest(ConsumerRecord<String, String> record) {
        handle("generated.ingest", record, payload -> {
            log.debug("generated.ingest event observed (artifact/research save-as-source will publish here)");
        });
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
}
