package com.noteweave.worker;

public interface ResearchOutboxPublisher {

    void publish(String topic, String messageKey, String payloadJson);
}
