package com.noteweave.task.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.task.model.Task;
import com.noteweave.task.model.TaskEventType;
import com.noteweave.task.model.TaskOutbox;
import com.noteweave.task.repository.TaskOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TaskOutboxService {

    private final TaskOutboxRepository taskOutboxRepository;
    private final ObjectMapper objectMapper;

    public TaskOutbox createTaskCreatedOutbox(Task task) {
        TaskOutbox outbox = new TaskOutbox();
        outbox.setTaskId(task.getId());
        outbox.setEventType(TaskEventType.TASK_CREATED);
        outbox.setAggregateType("TASK");
        outbox.setAggregateId(task.getId());
        outbox.setIdempotencyKey("task-created:" + task.getId() + ":retry:" + task.getRetryCount());
        outbox.setPayloadJson(writeJson(TaskOutboxMessage.builder()
                .taskId(task.getId())
                .taskType(task.getTaskType())
                .targetType(task.getTargetType())
                .targetId(task.getTargetId())
                .idempotencyKey(task.getIdempotencyKey())
                .createdAt(task.getCreatedAt() == null ? null : task.getCreatedAt().toString())
                .build()));
        return taskOutboxRepository.save(outbox);
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize task outbox payload", e);
        }
    }
}
