package com.noteweave.worker;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaResearchOutboxPublisher implements ResearchOutboxPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaResearchOutboxPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(String topic, String messageKey, String payloadJson) {
        kafkaTemplate.send(topic, messageKey, payloadJson).join();
    }
}
