package com.noteweave.task.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.task.model.Task;
import com.noteweave.task.model.TaskAttempt;
import com.noteweave.task.model.TaskEventType;
import com.noteweave.task.model.TaskStatus;
import com.noteweave.task.repository.TaskAttemptRepository;
import com.noteweave.task.repository.TaskRepository;
import com.noteweave.task.worker.TaskExecutionCancelledException;
import com.noteweave.task.worker.TaskExecutionContext;
import com.noteweave.task.worker.TaskExecutionTimeoutException;
import com.noteweave.task.worker.TaskResult;
import com.noteweave.task.worker.TaskWorker;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class TaskExecutionCoordinator {

    private final TaskRepository taskRepository;
    private final TaskAttemptRepository taskAttemptRepository;
    private final TaskWorkerRegistry taskWorkerRegistry;
    private final TaskEventService taskEventService;
    private final TaskCancellationChecker taskCancellationChecker;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public void consume(TaskOutboxMessage message) {
        ClaimedTask claimedTask = claimTask(message.getTaskId());
        if (claimedTask == null) {
            return;
        }

        Task task = claimedTask.task();
        TaskWorker worker = taskWorkerRegistry.find(task.getTaskType());
        if (worker == null) {
            markFailed(task.getId(), claimedTask.attemptNo(), ErrorCode.TASK_WORKER_NOT_FOUND.name(), "No task worker registered");
            return;
        }

        try {
            TaskExecutionContext context = new TaskExecutionContext(task, objectMapper, taskCancellationChecker, taskEventService);
            TaskResult result = worker.execute(context);
            markSuccess(task.getId(), claimedTask.attemptNo(), result);
        } catch (TaskExecutionCancelledException ex) {
            markCancelled(task.getId(), claimedTask.attemptNo(), ex.getMessage());
        } catch (TaskExecutionTimeoutException ex) {
            markTimeout(task.getId(), claimedTask.attemptNo(), ex.getMessage());
        } catch (Exception ex) {
            markFailed(task.getId(), claimedTask.attemptNo(), ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    protected ClaimedTask claimTask(Long taskId) {
        return transactionTemplate.execute(status -> {
            Task task = taskRepository.findByIdForUpdate(taskId).orElse(null);
            if (task == null) {
                return null;
            }
            if (task.getTaskStatus() != TaskStatus.PENDING) {
                return null;
            }

            LocalDateTime now = LocalDateTime.now();
            task.setTaskStatus(TaskStatus.RUNNING);
            task.setStartedAt(now);
            task.setFinishedAt(null);
            task.setErrorMessage(null);

            int attemptNo = task.getRetryCount() + 1;
            TaskAttempt attempt = new TaskAttempt();
            attempt.setTaskId(task.getId());
            attempt.setAttemptNo(attemptNo);
            attempt.setWorkerId(task.getTaskType().name() + "-local-worker");
            attempt.setStatus(TaskStatus.RUNNING);
            attempt.setStartedAt(now);
            taskRepository.save(task);
            taskAttemptRepository.save(attempt);
            taskEventService.appendEvent(
                    task.getId(),
                    TaskEventType.TASK_STARTED,
                    TaskStatus.PENDING,
                    TaskStatus.RUNNING,
                    "Task execution started",
                    null,
                    null
            );
            return new ClaimedTask(copyTask(task), attemptNo);
        });
    }

    protected void markSuccess(Long taskId, int attemptNo, TaskResult result) {
        transactionTemplate.executeWithoutResult(status -> {
            Task task = getTaskForUpdate(taskId);
            TaskStatus fromStatus = task.getTaskStatus();
            task.setTaskStatus(TaskStatus.SUCCESS);
            task.setCancelRequested(false);
            task.setFinishedAt(LocalDateTime.now());
            task.setErrorMessage(null);
            task.setOutputJson(writeJson(result == null ? null : result.getOutput()));
            task.setResultRefType(result == null ? null : result.getResultRefType());
            task.setResultRefId(result == null ? null : result.getResultRefId());
            taskRepository.save(task);

            TaskAttempt attempt = getAttempt(taskId, attemptNo);
            attempt.setStatus(TaskStatus.SUCCESS);
            attempt.setFinishedAt(task.getFinishedAt());
            attempt.setErrorCode(null);
            attempt.setErrorMessage(null);
            taskAttemptRepository.save(attempt);

            taskEventService.appendEvent(taskId, TaskEventType.TASK_SUCCEEDED, fromStatus, TaskStatus.SUCCESS, "Task succeeded", null, null);
        });
    }

    protected void markFailed(Long taskId, int attemptNo, String errorCode, String errorMessage) {
        transactionTemplate.executeWithoutResult(status -> {
            Task task = getTaskForUpdate(taskId);
            TaskStatus fromStatus = task.getTaskStatus();
            task.setTaskStatus(TaskStatus.FAILED);
            task.setCancelRequested(false);
            task.setFinishedAt(LocalDateTime.now());
            task.setErrorMessage(errorMessage);
            taskRepository.save(task);

            TaskAttempt attempt = getAttempt(taskId, attemptNo);
            attempt.setStatus(TaskStatus.FAILED);
            attempt.setFinishedAt(task.getFinishedAt());
            attempt.setErrorCode(errorCode);
            attempt.setErrorMessage(errorMessage);
            taskAttemptRepository.save(attempt);

            taskEventService.appendEvent(
                    taskId,
                    TaskEventType.TASK_FAILED,
                    fromStatus,
                    TaskStatus.FAILED,
                    errorMessage,
                    null,
                    null
            );
        });
    }

    protected void markTimeout(Long taskId, int attemptNo, String errorMessage) {
        transactionTemplate.executeWithoutResult(status -> {
            Task task = getTaskForUpdate(taskId);
            TaskStatus fromStatus = task.getTaskStatus();
            task.setTaskStatus(TaskStatus.TIMEOUT);
            task.setCancelRequested(false);
            task.setFinishedAt(LocalDateTime.now());
            task.setErrorMessage(errorMessage);
            taskRepository.save(task);

            TaskAttempt attempt = getAttempt(taskId, attemptNo);
            attempt.setStatus(TaskStatus.TIMEOUT);
            attempt.setFinishedAt(task.getFinishedAt());
            attempt.setErrorCode(TaskStatus.TIMEOUT.name());
            attempt.setErrorMessage(errorMessage);
            taskAttemptRepository.save(attempt);

            taskEventService.appendEvent(
                    taskId,
                    TaskEventType.TASK_TIMED_OUT,
                    fromStatus,
                    TaskStatus.TIMEOUT,
                    errorMessage,
                    null,
                    null
            );
        });
    }

    protected void markCancelled(Long taskId, int attemptNo, String message) {
        transactionTemplate.executeWithoutResult(status -> {
            Task task = getTaskForUpdate(taskId);
            TaskStatus fromStatus = task.getTaskStatus();
            task.setTaskStatus(TaskStatus.CANCELLED);
            task.setCancelRequested(false);
            task.setFinishedAt(LocalDateTime.now());
            task.setErrorMessage(message);
            taskRepository.save(task);

            TaskAttempt attempt = getAttempt(taskId, attemptNo);
            attempt.setStatus(TaskStatus.CANCELLED);
            attempt.setFinishedAt(task.getFinishedAt());
            attempt.setErrorCode(TaskStatus.CANCELLED.name());
            attempt.setErrorMessage(message);
            taskAttemptRepository.save(attempt);

            taskEventService.appendEvent(
                    taskId,
                    TaskEventType.TASK_CANCELLED,
                    fromStatus,
                    TaskStatus.CANCELLED,
                    message,
                    null,
                    null
            );
        });
    }

    private Task getTaskForUpdate(Long taskId) {
        return taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));
    }

    private TaskAttempt getAttempt(Long taskId, int attemptNo) {
        return taskAttemptRepository.findByTaskIdAndAttemptNo(taskId, attemptNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND, "Task attempt not found"));
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize task output", e);
        }
    }

    private Task copyTask(Task source) {
        Task task = new Task();
        task.setId(source.getId());
        task.setUserId(source.getUserId());
        task.setSpaceId(source.getSpaceId());
        task.setResearchProjectId(source.getResearchProjectId());
        task.setTaskType(source.getTaskType());
        task.setTargetType(source.getTargetType());
        task.setTargetId(source.getTargetId());
        task.setTaskStatus(source.getTaskStatus());
        task.setIdempotencyKey(source.getIdempotencyKey());
        task.setInputJson(source.getInputJson());
        task.setOutputJson(source.getOutputJson());
        task.setErrorMessage(source.getErrorMessage());
        task.setCancelRequested(source.isCancelRequested());
        task.setRetryCount(source.getRetryCount());
        task.setMaxRetryCount(source.getMaxRetryCount());
        task.setResultRefType(source.getResultRefType());
        task.setResultRefId(source.getResultRefId());
        task.setStartedAt(source.getStartedAt());
        task.setFinishedAt(source.getFinishedAt());
        task.setCreatedAt(source.getCreatedAt());
        task.setUpdatedAt(source.getUpdatedAt());
        return task;
    }

    protected record ClaimedTask(Task task, int attemptNo) {
    }
}
