package com.noteweave.worker;

public interface ArtifactOutboxPublisher {

    void publish(String topic, String messageKey, String payloadJson, String deliveryToken);
}
