package com.noteweave.infra;

/** Topic-agnostic Kafka publishing boundary shared by durable outbox dispatchers. */
@FunctionalInterface
public interface KafkaMessagePublisher {

    void publish(String topic, String messageKey, String payloadJson);
}
