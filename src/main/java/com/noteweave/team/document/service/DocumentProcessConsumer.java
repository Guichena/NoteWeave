package com.noteweave.team.document.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.team.document.dto.DocumentProcessTaskPayload;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DocumentProcessConsumer {

    private final ObjectMapper objectMapper;
    private final DocumentProcessingService documentProcessingService;

    @KafkaListener(
            topics = "${noteweave.kafka.topics.document-process:noteweave.document}",
            groupId = "${noteweave.kafka.consumer-groups.document-process:noteweave-document-process-worker}"
    )
    public void consume(ConsumerRecord<String, String> record) {
        try {
            DocumentProcessTaskPayload payload = objectMapper.readValue(record.value(), DocumentProcessTaskPayload.class);
            DocumentProcessTaskPayload taskOnlyPayload = DocumentProcessTaskPayload.builder()
                    .taskId(payload.getTaskId())
                    .build();
            documentProcessingService.process(taskOnlyPayload);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to consume document process kafka message", ex);
        }
    }
}
