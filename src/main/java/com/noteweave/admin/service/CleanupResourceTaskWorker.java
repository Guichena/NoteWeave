package com.noteweave.admin.service;

import com.noteweave.admin.dto.CleanupExecutionTaskPayload;
import com.noteweave.task.model.TaskType;
import com.noteweave.task.worker.TaskExecutionContext;
import com.noteweave.task.worker.TaskResult;
import com.noteweave.task.worker.TaskWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CleanupResourceTaskWorker implements TaskWorker {

    private final ResourceCleanupService resourceCleanupService;

    @Override
    public TaskType taskType() {
        return TaskType.CLEANUP_RESOURCE;
    }

    @Override
    public TaskResult execute(TaskExecutionContext context) {
        CleanupExecutionTaskPayload payload = context.readInput(CleanupExecutionTaskPayload.class);
        return resourceCleanupService.executeQueuedJob(payload, context);
    }
}
