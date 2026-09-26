package com.noteweave.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Keeps the application observable when Kafka is disabled without reviving the HTTP task path. */
@Component
@ConditionalOnProperty(name = "noteweave.kafka.enabled", havingValue = "false")
public class KafkaDisabledArtifactOutboxPublisher implements ArtifactOutboxPublisher {

    @Override
    public void publish(String topic, String messageKey, String payloadJson, String deliveryToken) {
        throw new IllegalStateException(
                "Artifact command dispatch requires noteweave.kafka.enabled=true"
        );
    }
}
