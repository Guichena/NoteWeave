package com.noteweave.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.retrieval.projection.SourceRetrievalProjectionFinalizer;
import com.noteweave.retrieval.provider.RetrievalProviderException;
import com.noteweave.source.SourceParseFailureFinalizer;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 消费者重试耗尽后的业务收尾：消息已经发往 DLT，这里把对应的资料和任务标记为终态，
 * 避免资料一直停在处理中。收尾失败只记录日志，不能再抛出，否则记录会被重新投递。
 */
@Component
public class KafkaDeadLetterTaskRecovery {
    private static final Logger log = LoggerFactory.getLogger(KafkaDeadLetterTaskRecovery.class);

    private final ObjectMapper objectMapper;
    private final SourceRetrievalProjectionFinalizer projectionFinalizer;
    private final SourceParseFailureFinalizer sourceParseFailureFinalizer;
    private final String retrievalProjectionTopic;
    private final String sourceParseTopic;

    public KafkaDeadLetterTaskRecovery(
            ObjectMapper objectMapper,
            SourceRetrievalProjectionFinalizer projectionFinalizer,
            SourceParseFailureFinalizer sourceParseFailureFinalizer,
            @Value("${noteweave.kafka.topics.retrieval-projection}") String retrievalProjectionTopic,
            @Value("${noteweave.kafka.topics.source-parse}") String sourceParseTopic
    ) {
        this.objectMapper = objectMapper;
        this.projectionFinalizer = projectionFinalizer;
        this.sourceParseFailureFinalizer = sourceParseFailureFinalizer;
        this.retrievalProjectionTopic = retrievalProjectionTopic;
        this.sourceParseTopic = sourceParseTopic;
    }

    public void recover(ConsumerRecord<?, ?> record, Exception exception) {
        // 解析和切片阶段失败时资料停在解析中；向量化和索引阶段失败时资料停在索引中
        if (sourceParseTopic.equals(record.topic())
                || com.noteweave.source.SourcePipelineStages.TOPIC_CHUNK.equals(record.topic())) {
            recoverSourceParse(record, exception);
            return;
        }
        if (!retrievalProjectionTopic.equals(record.topic())
                && !com.noteweave.source.SourcePipelineStages.TOPIC_EMBED.equals(record.topic())
                && !com.noteweave.source.SourcePipelineStages.TOPIC_INDEX.equals(record.topic())) {
            return;
        }
        Map<String, Object> payload = readPayload(record, "Retrieval projection");
        if (payload == null) {
            return;
        }
        String workspaceId = text(payload.get("workspaceId"));
        String sourceId = text(payload.get("sourceId"));
        String snapshotId = text(payload.get("sourceSnapshotId"));
        String taskId = text(payload.get("taskId"));
        if (isBlank(workspaceId) || isBlank(sourceId) || isBlank(snapshotId)) {
            log.warn("Retrieval projection DLT payload is missing identity: topic={}, partition={}, offset={}",
                    record.topic(), record.partition(), record.offset());
            return;
        }
        projectionFinalizer.finalizeFailed(
                workspaceId,
                sourceId,
                snapshotId,
                taskId,
                errorCode(exception)
        );
    }

    private void recoverSourceParse(ConsumerRecord<?, ?> record, Exception exception) {
        Map<String, Object> payload = readPayload(record, "Source parse");
        if (payload == null) {
            return;
        }
        String taskId = text(payload.get("taskId"));
        String workspaceId = text(payload.get("workspaceId"));
        String sourceId = text(payload.get("sourceId"));
        String snapshotId = text(payload.get("snapshotId"));
        if (isBlank(taskId) || isBlank(workspaceId) || isBlank(sourceId) || isBlank(snapshotId)) {
            log.warn("Source parse DLT payload is missing identity: topic={}, partition={}, offset={}",
                    record.topic(), record.partition(), record.offset());
            return;
        }
        // 文档本身无法解析（类型不支持、PDF 无文本等）属于业务错误，重试没有意义；
        // 其余异常（例如读取对象存储失败）可能是暂时性的，标记为可重试。
        BusinessException businessFailure = findCause(exception, BusinessException.class);
        String errorCode = businessFailure != null ? businessFailure.code() : "SOURCE_PARSE_FAILED";
        String errorMessage = businessFailure != null ? businessFailure.getMessage() : "资料解析过程中出错";
        try {
            sourceParseFailureFinalizer.finalizeProcessingFailed(
                    taskId, workspaceId, sourceId, snapshotId, errorCode, errorMessage, businessFailure == null);
        } catch (RuntimeException finalizeFailure) {
            log.warn("Source parse DLT finalization failed: taskId={}, sourceId={}, reason={}",
                    taskId, sourceId, finalizeFailure.getMessage(), finalizeFailure);
        }
    }

    private Map<String, Object> readPayload(ConsumerRecord<?, ?> record, String label) {
        try {
            return objectMapper.readValue(String.valueOf(record.value()), new TypeReference<>() {
            });
        } catch (Exception parseFailure) {
            log.warn("{} DLT payload cannot be parsed: topic={}, partition={}, offset={}",
                    label, record.topic(), record.partition(), record.offset());
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
