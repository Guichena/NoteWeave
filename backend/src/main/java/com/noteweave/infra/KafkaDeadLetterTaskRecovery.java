package com.noteweave.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.retrieval.projection.SourceRetrievalProjectionFinalizer;
import com.noteweave.retrieval.provider.RetrievalProviderException;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class KafkaDeadLetterTaskRecovery {
    private static final Logger log = LoggerFactory.getLogger(KafkaDeadLetterTaskRecovery.class);

    private final ObjectMapper objectMapper;
    private final SourceRetrievalProjectionFinalizer projectionFinalizer;
    private final String retrievalProjectionTopic;

    public KafkaDeadLetterTaskRecovery(
            ObjectMapper objectMapper,
            SourceRetrievalProjectionFinalizer projectionFinalizer,
            @Value("${noteweave.kafka.topics.retrieval-projection}") String retrievalProjectionTopic
    ) {
        this.objectMapper = objectMapper;
        this.projectionFinalizer = projectionFinalizer;
        this.retrievalProjectionTopic = retrievalProjectionTopic;
    }

    public void recover(ConsumerRecord<?, ?> record, Exception exception) {
        if (!retrievalProjectionTopic.equals(record.topic())) {
            return;
        }
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(String.valueOf(record.value()), new TypeReference<>() {
            });
        } catch (Exception parseFailure) {
            log.warn("Retrieval projection DLT payload cannot be parsed: topic={}, partition={}, offset={}",
                    record.topic(), record.partition(), record.offset());
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

    private String errorCode(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof RetrievalProviderException providerException) {
                return providerException.errorCode();
            }
            current = current.getCause();
        }
        return "RETRIEVAL_PROJECTION_FAILED";
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
