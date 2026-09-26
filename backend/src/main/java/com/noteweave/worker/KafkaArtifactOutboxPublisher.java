package com.noteweave.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.infra.KafkaMessagePublisher;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Publishes a minimal, versioned Artifact command to the durable Kafka transport. */
@Component
@ConditionalOnProperty(name = "noteweave.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaArtifactOutboxPublisher implements ArtifactOutboxPublisher {

    private final KafkaMessagePublisher kafkaPublisher;
    private final ObjectMapper objectMapper;

    public KafkaArtifactOutboxPublisher(KafkaMessagePublisher kafkaPublisher, ObjectMapper objectMapper) {
        this.kafkaPublisher = kafkaPublisher;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(String topic, String messageKey, String payloadJson, String deliveryToken) {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("schema_version", "artifact-command.v1");
        command.put("task_id", extractTaskId(payloadJson));
        command.put("delivery_token", requireText(deliveryToken, "Artifact delivery token is missing"));
        kafkaPublisher.publish(topic, messageKey, write(command));
    }

    private String extractTaskId(String payloadJson) {
        try {
            return requireText(
                    objectMapper.readTree(payloadJson).path("task_id").asText(""),
                    "Artifact outbox payload is missing task_id"
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Artifact outbox payload is invalid", exception);
        }
    }

    private String write(Map<String, Object> command) {
        try {
            return objectMapper.writeValueAsString(command);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Artifact command serialization failed", exception);
        }
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
