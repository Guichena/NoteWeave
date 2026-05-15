package com.noteweave.task.service;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.task.model.Task;
import com.noteweave.task.model.TaskStatus;
import com.noteweave.task.repository.TaskRepository;
import com.noteweave.task.worker.TaskExecutionCancelledException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TaskCancellationChecker {

    private final TaskRepository taskRepository;

    public void throwIfCancellationRequested(Long taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));
        if (task.getTaskStatus() == TaskStatus.CANCELLED || task.isCancelRequested()) {
            throw new TaskExecutionCancelledException("Task cancellation requested");
        }
    }
}
