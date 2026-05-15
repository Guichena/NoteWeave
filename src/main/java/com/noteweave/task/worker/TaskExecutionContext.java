package com.noteweave.task.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.task.model.Task;
import com.noteweave.task.service.TaskCancellationChecker;
import com.noteweave.task.service.TaskEventService;
import java.util.Map;

public class TaskExecutionContext {

    private final Task task;
    private final ObjectMapper objectMapper;
    private final TaskCancellationChecker taskCancellationChecker;
    private final TaskEventService taskEventService;

    public TaskExecutionContext(
            Task task,
            ObjectMapper objectMapper,
            TaskCancellationChecker taskCancellationChecker,
            TaskEventService taskEventService
    ) {
        this.task = task;
        this.objectMapper = objectMapper;
        this.taskCancellationChecker = taskCancellationChecker;
        this.taskEventService = taskEventService;
    }

    public Task task() {
        return task;
    }

    public <T> T readInput(Class<T> type) {
        if (task.getInputJson() == null || task.getInputJson().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Task input is required");
        }
        try {
            return objectMapper.readValue(task.getInputJson(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize task input", e);
        }
    }

    public void ensureNotCancelled() {
        taskCancellationChecker.throwIfCancellationRequested(task.getId());
    }

    public void publishProgress(String message, Map<String, Object> payload) {
        taskEventService.appendProgressEvent(task.getId(), message, payload);
    }
}
