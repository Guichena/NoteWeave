package com.noteweave.task.service;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TaskKafkaPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publish(String topic, TaskOutboxMessage message) {
        try {
            kafkaTemplate.send(topic, String.valueOf(message.getTaskId()), message).get();
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.KAFKA_DISPATCH_FAILED, "failed to dispatch task kafka message");
        }
    }
}
