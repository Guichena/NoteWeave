package com.noteweave.task.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.task.model.TaskEvent;
import com.noteweave.task.model.TaskEventType;
import com.noteweave.task.model.TaskStatus;
import com.noteweave.task.repository.TaskEventRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TaskEventService {

    private final TaskEventRepository taskEventRepository;
    private final ObjectMapper objectMapper;

    public TaskEvent appendEvent(
            Long taskId,
            TaskEventType eventType,
            TaskStatus fromStatus,
            TaskStatus toStatus,
            String message,
            Object payload,
            Long createdBy
    ) {
        TaskEvent event = new TaskEvent();
        event.setTaskId(taskId);
        event.setEventType(eventType);
        event.setFromStatus(fromStatus);
        event.setToStatus(toStatus);
        event.setMessage(message);
        event.setPayloadJson(writeJson(payload));
        event.setCreatedBy(createdBy);
        return taskEventRepository.save(event);
    }

    public void appendProgressEvent(Long taskId, String message, Map<String, Object> payload) {
        appendEvent(taskId, TaskEventType.TASK_PROGRESS, TaskStatus.RUNNING, TaskStatus.RUNNING, message, payload, null);
    }

    private String writeJson(Object payload) {
        if (payload == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize task event payload", e);
        }
    }
}
