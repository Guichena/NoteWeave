package com.noteweave.admin.service;

import com.noteweave.admin.dto.MarkTaskFailedRequest;
import com.noteweave.admin.model.AuditAction;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.common.security.CurrentUser;
import com.noteweave.task.dto.TaskResponse;
import com.noteweave.task.model.Task;
import com.noteweave.task.model.TaskAttempt;
import com.noteweave.task.model.TaskEventType;
import com.noteweave.task.model.TaskStatus;
import com.noteweave.task.repository.TaskAttemptRepository;
import com.noteweave.task.repository.TaskRepository;
import com.noteweave.task.service.TaskEventService;
import com.noteweave.task.service.TaskService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminTaskService {

    private final TaskService taskService;
    private final TaskRepository taskRepository;
    private final TaskAttemptRepository taskAttemptRepository;
    private final TaskEventService taskEventService;
    private final AuditLogService auditLogService;
    private final AdminTaskRetryValidator retryValidator;

    @Transactional
    public TaskResponse retry(CurrentUser operator, Long taskId) {
        Task beforeTask = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));
        retryValidator.validate(beforeTask);
        TaskResponse before = taskService.getTask(operator, taskId);
        TaskResponse response = taskService.retryTask(operator, taskId);
        auditLogService.record(operator.userId(), beforeTask.getSpaceId(), AuditAction.TASK_RETRY, "TASK", taskId, before, response);
        return response;
    }

    @Transactional
    public TaskResponse cancel(CurrentUser operator, Long taskId) {
        Task beforeTask = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));
        TaskResponse before = taskService.getTask(operator, taskId);
        taskService.cancelTask(operator, taskId);
        TaskResponse after = taskService.getTask(operator, taskId);
        auditLogService.record(operator.userId(), beforeTask.getSpaceId(), AuditAction.TASK_CANCEL, "TASK", taskId, before, after);
        return after;
    }

    @Transactional
    public TaskResponse markFailed(CurrentUser operator, Long taskId, MarkTaskFailedRequest request) {
        Task task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));
        TaskResponse before = taskService.getTask(operator, taskId);
        if (task.getTaskStatus() == TaskStatus.SUCCESS
                || task.getTaskStatus() == TaskStatus.CANCELLED
                || task.getTaskStatus() == TaskStatus.FAILED
                || task.getTaskStatus() == TaskStatus.TIMEOUT) {
            throw new BusinessException(ErrorCode.TASK_INVALID_STATUS, "Completed tasks cannot be marked failed");
        }

        TaskStatus fromStatus = task.getTaskStatus();
        task.setTaskStatus(TaskStatus.FAILED);
        task.setErrorMessage(request.getReason().trim());
        if (fromStatus == TaskStatus.RUNNING) {
            task.setCancelRequested(true);
        } else {
            task.setCancelRequested(false);
            task.setFinishedAt(LocalDateTime.now());
        }
        taskRepository.save(task);

        if (fromStatus == TaskStatus.PENDING) {
            List<TaskAttempt> attempts = taskAttemptRepository.findByTaskIdOrderByAttemptNoAsc(task.getId());
            if (attempts.isEmpty()) {
                TaskAttempt attempt = new TaskAttempt();
                attempt.setTaskId(task.getId());
                attempt.setAttemptNo(Math.max(task.getRetryCount(), 0) + 1);
                attempt.setWorkerId("admin-mark-failed");
                attempt.setStatus(TaskStatus.FAILED);
                attempt.setStartedAt(task.getStartedAt());
                attempt.setFinishedAt(task.getFinishedAt());
                attempt.setErrorCode("ADMIN_MARK_FAILED");
                attempt.setErrorMessage(task.getErrorMessage());
                taskAttemptRepository.save(attempt);
            }
        }

        taskEventService.appendEvent(
                task.getId(),
                TaskEventType.TASK_FAILED,
                fromStatus,
                TaskStatus.FAILED,
                task.getErrorMessage(),
                Map.of("adminMarkFailed", true),
                operator.userId()
        );
        TaskResponse after = taskService.getTask(operator, taskId);
        auditLogService.record(operator.userId(), task.getSpaceId(), AuditAction.TASK_MARK_FAILED, "TASK", taskId, before, after);
        return after;
    }
}
