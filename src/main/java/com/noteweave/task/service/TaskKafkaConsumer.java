package com.noteweave.task.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TaskKafkaConsumer {

    private final TaskExecutionCoordinator taskExecutionCoordinator;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${noteweave.kafka.topics.task:noteweave.task}",
            groupId = "${noteweave.kafka.consumer-groups.task:noteweave-task-worker}"
    )
    public void consume(ConsumerRecord<String, String> record) {
        try {
            TaskOutboxMessage message = objectMapper.readValue(record.value(), TaskOutboxMessage.class);
            taskExecutionCoordinator.consume(message);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to consume task kafka message", ex);
        }
    }
}
