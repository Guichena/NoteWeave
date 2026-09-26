package com.noteweave.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.infra.KafkaMessagePublisher;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class KafkaArtifactOutboxPublisherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldPublishMinimalVersionedCommandWithDeliveryToken() throws Exception {
        AtomicReference<String> topic = new AtomicReference<>();
        AtomicReference<String> key = new AtomicReference<>();
        AtomicReference<String> payload = new AtomicReference<>();
        KafkaMessagePublisher kafka = (publishedTopic, messageKey, payloadJson) -> {
            topic.set(publishedTopic);
            key.set(messageKey);
            payload.set(payloadJson);
        };
        KafkaArtifactOutboxPublisher publisher = new KafkaArtifactOutboxPublisher(kafka, objectMapper);

        publisher.publish(
                "noteweave.artifact.job",
                "artifact-1",
                "{\"task_id\":\"task-1\",\"target_id\":\"artifact-1\",\"secret\":\"must-not-leak\"}",
                "delivery-token-1"
        );

        assertThat(topic.get()).isEqualTo("noteweave.artifact.job");
        assertThat(key.get()).isEqualTo("artifact-1");
        JsonNode command = objectMapper.readTree(payload.get());
        assertThat(command.fieldNames()).toIterable()
                .containsExactly("schema_version", "task_id", "delivery_token");
        assertThat(command.path("schema_version").asText()).isEqualTo("artifact-command.v1");
        assertThat(command.path("task_id").asText()).isEqualTo("task-1");
        assertThat(command.path("delivery_token").asText()).isEqualTo("delivery-token-1");
        assertThat(payload.get()).doesNotContain("must-not-leak");
    }

    @Test
    void shouldRejectCommandWithoutTaskOrDeliveryIdentityBeforePublishing() {
        AtomicReference<String> payload = new AtomicReference<>();
        KafkaArtifactOutboxPublisher publisher = new KafkaArtifactOutboxPublisher(
                (topic, key, body) -> payload.set(body), objectMapper
        );

        assertThatThrownBy(() -> publisher.publish("topic", "key", "{}", "delivery"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("task_id");
        assertThatThrownBy(() -> publisher.publish("topic", "key", "{\"task_id\":\"task-1\"}", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delivery token");
        assertThat(payload.get()).isNull();
    }
}
