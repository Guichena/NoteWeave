package com.noteweave.team.document.service;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.team.document.dto.DocumentProcessTaskPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DocumentProcessPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publish(String topic, Long taskId, DocumentProcessTaskPayload payload) {
        try {
            kafkaTemplate.send(topic, String.valueOf(taskId), payload).get();
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.KAFKA_DISPATCH_FAILED, "failed to dispatch kafka message");
        }
    }
}
