package com.noteweave.task.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.api.PageResponse;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.common.security.CurrentUser;
import com.noteweave.permission.service.ResourceAccessService;
import com.noteweave.space.model.SpaceMember;
import com.noteweave.space.model.SpaceMemberStatus;
import com.noteweave.space.repository.SpaceMemberRepository;
import com.noteweave.task.dto.TaskEventQuery;
import com.noteweave.task.dto.TaskEventResponse;
import com.noteweave.task.dto.TaskQuery;
import com.noteweave.task.dto.TaskResponse;
import com.noteweave.task.model.Task;
import com.noteweave.task.model.TaskAttempt;
import com.noteweave.task.model.TaskEvent;
import com.noteweave.task.model.TaskEventType;
import com.noteweave.task.model.TaskStatus;
import com.noteweave.task.repository.TaskAttemptRepository;
import com.noteweave.task.repository.TaskEventRepository;
import com.noteweave.task.repository.TaskRepository;
import com.noteweave.user.model.UserSystemRole;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TaskService {

    private static final Set<String> ALLOWED_TASK_SORT_FIELDS = Set.of("id", "createdAt", "updatedAt", "startedAt", "finishedAt");
    private static final Set<String> ALLOWED_EVENT_SORT_FIELDS = Set.of("id", "createdAt");
    private static final String REDACTED_MESSAGE = "[REDACTED]";

    private final TaskRepository taskRepository;
    private final TaskAttemptRepository taskAttemptRepository;
    private final TaskEventRepository taskEventRepository;
    private final TaskEventService taskEventService;
    private final TaskOutboxService taskOutboxService;
    private final ResourceAccessService resourceAccessService;
    private final SpaceMemberRepository spaceMemberRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public TaskResponse createTask(TaskCreateCommand command) {
        Task existing = taskRepository.findByIdempotencyKey(command.getIdempotencyKey()).orElse(null);
        if (existing != null) {
            return toTaskResponse(existing, false);
        }

        Task task = new Task();
        task.setUserId(command.getUserId());
        task.setSpaceId(command.getSpaceId());
        task.setResearchProjectId(command.getResearchProjectId());
        task.setTaskType(command.getTaskType());
        task.setTargetType(normalizeOptional(command.getTargetType()));
        task.setTargetId(command.getTargetId());
        task.setTaskStatus(TaskStatus.PENDING);
        task.setIdempotencyKey(command.getIdempotencyKey());
        task.setInputJson(writeJson(command.getInput()));
        task.setMaxRetryCount(Math.max(command.getMaxRetryCount(), 0));
        task = taskRepository.save(task);

        taskEventService.appendEvent(
                task.getId(),
                TaskEventType.TASK_CREATED,
                null,
                TaskStatus.PENDING,
                "Task created",
                Map.of("taskType", task.getTaskType().name(), "idempotencyKey", task.getIdempotencyKey()),
                task.getUserId()
        );
        taskOutboxService.createTaskCreatedOutbox(task);
        return toTaskResponse(task, false);
    }

    public PageResponse<TaskResponse> listTasks(CurrentUser currentUser, TaskQuery query) {
        Pageable pageable = buildPageable(query.getPage(), query.getPageSize(), query.getSort(), ALLOWED_TASK_SORT_FIELDS, Sort.by(Sort.Direction.DESC, "createdAt"));
        Specification<Task> specification = buildTaskSpecification(currentUser, query);
        Page<Task> page = taskRepository.findAll(specification, pageable);
        boolean redactSensitiveFields = shouldRedactSensitiveFields(currentUser);
        List<TaskResponse> items = page.getContent().stream()
                .map(task -> toTaskResponse(task, redactSensitiveFields))
                .toList();
        return PageResponse.<TaskResponse>builder()
                .items(items)
                .page(pageable.getPageNumber() + 1)
                .pageSize(pageable.getPageSize())
                .total(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .sort(toSortExpression(pageable.getSort()))
                .filters(Map.of())
                .build();
    }

    public TaskResponse getTask(CurrentUser currentUser, Long taskId) {
        Task task = getRequiredTask(taskId);
        resourceAccessService.requireViewTask(currentUser, task);
        return toTaskResponse(task, shouldRedactSensitiveFields(currentUser));
    }

    public PageResponse<TaskEventResponse> getTaskEvents(CurrentUser currentUser, Long taskId, TaskEventQuery query) {
        Task task = getRequiredTask(taskId);
        resourceAccessService.requireViewTask(currentUser, task);
        Pageable pageable = buildPageable(query.getPage(), query.getPageSize(), query.getSort(), ALLOWED_EVENT_SORT_FIELDS, Sort.by(Sort.Direction.ASC, "createdAt"));
        Page<TaskEvent> page = taskEventRepository.findByTaskId(taskId, pageable);
        return PageResponse.<TaskEventResponse>builder()
                .items(page.getContent().stream().map(this::toTaskEventResponse).toList())
                .page(pageable.getPageNumber() + 1)
                .pageSize(pageable.getPageSize())
                .total(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .sort(toSortExpression(pageable.getSort()))
                .filters(Map.of())
                .build();
    }

    @Transactional
    public void cancelTask(CurrentUser currentUser, Long taskId) {
        Task task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));
        resourceAccessService.requireOperateTask(currentUser, task);

        if (task.getTaskStatus() == TaskStatus.PENDING) {
            task.setTaskStatus(TaskStatus.CANCELLED);
            task.setFinishedAt(java.time.LocalDateTime.now());
            task.setCancelRequested(false);
            task.setErrorMessage("Task cancelled before execution");
            taskRepository.save(task);
            taskEventService.appendEvent(
                    task.getId(),
                    TaskEventType.TASK_CANCELLED,
                    TaskStatus.PENDING,
                    TaskStatus.CANCELLED,
                    "Task cancelled before execution",
                    null,
                    currentUser.userId()
            );
            return;
        }

        if (task.getTaskStatus() == TaskStatus.RUNNING) {
            if (!task.isCancelRequested()) {
                task.setCancelRequested(true);
                taskRepository.save(task);
                taskEventService.appendEvent(
                        task.getId(),
                        TaskEventType.TASK_CANCEL_REQUESTED,
                        TaskStatus.RUNNING,
                        TaskStatus.RUNNING,
                        "Task cancellation requested",
                        null,
                        currentUser.userId()
                );
            }
            return;
        }

        throw new BusinessException(ErrorCode.TASK_INVALID_STATUS, "Only PENDING or RUNNING tasks can be cancelled");
    }

    @Transactional
    public TaskResponse retryTask(CurrentUser currentUser, Long taskId) {
        Task task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));
        resourceAccessService.requireOperateTask(currentUser, task);

        if (task.getTaskStatus() != TaskStatus.FAILED && task.getTaskStatus() != TaskStatus.TIMEOUT) {
            throw new BusinessException(ErrorCode.TASK_RETRY_NOT_ALLOWED, "Only FAILED or TIMEOUT tasks can be retried");
        }
        if (task.getRetryCount() >= task.getMaxRetryCount()) {
            throw new BusinessException(ErrorCode.TASK_RETRY_NOT_ALLOWED, "Task retry limit exceeded");
        }

        TaskStatus fromStatus = task.getTaskStatus();
        task.setTaskStatus(TaskStatus.PENDING);
        task.setRetryCount(task.getRetryCount() + 1);
        task.setCancelRequested(false);
        task.setStartedAt(null);
        task.setFinishedAt(null);
        task.setErrorMessage(null);
        task.setOutputJson(null);
        task.setResultRefType(null);
        task.setResultRefId(null);
        taskRepository.save(task);

        taskEventService.appendEvent(
                task.getId(),
                TaskEventType.TASK_RETRY_CREATED,
                fromStatus,
                TaskStatus.PENDING,
                "Task retry created",
                Map.of("retryCount", task.getRetryCount()),
                currentUser.userId()
        );
        taskOutboxService.createTaskCreatedOutbox(task);
        return toTaskResponse(task, shouldRedactSensitiveFields(currentUser));
    }

    public Task getRequiredTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));
    }

    public List<TaskAttempt> getAttempts(Long taskId) {
        return taskAttemptRepository.findByTaskIdOrderByAttemptNoAsc(taskId);
    }

    private Specification<Task> buildTaskSpecification(CurrentUser currentUser, TaskQuery query) {
        return (root, criteriaQuery, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (currentUser.systemRole() != UserSystemRole.ADMIN) {
                List<SpaceMember> memberships = spaceMemberRepository.findByUserIdAndStatus(currentUser.userId(), SpaceMemberStatus.ACTIVE);
                List<Long> visibleSpaceIds = memberships.stream()
                        .map(SpaceMember::getSpaceId)
                        .distinct()
                        .toList();
                if (visibleSpaceIds.isEmpty()) {
                    return criteriaBuilder.disjunction();
                }
                predicates.add(root.get("spaceId").in(visibleSpaceIds));
            }

            if (query.getSpaceId() != null) {
                predicates.add(criteriaBuilder.equal(root.get("spaceId"), query.getSpaceId()));
            }
            if (query.getResearchProjectId() != null) {
                predicates.add(criteriaBuilder.equal(root.get("researchProjectId"), query.getResearchProjectId()));
            }
            if (query.getTaskType() != null) {
                predicates.add(criteriaBuilder.equal(root.get("taskType"), query.getTaskType()));
            }
            if (query.getTaskStatus() != null) {
                predicates.add(criteriaBuilder.equal(root.get("taskStatus"), query.getTaskStatus()));
            }
            if (query.getTargetType() != null && !query.getTargetType().isBlank()) {
                predicates.add(criteriaBuilder.equal(root.get("targetType"), query.getTargetType().trim()));
            }
            if (query.getTargetId() != null) {
                predicates.add(criteriaBuilder.equal(root.get("targetId"), query.getTargetId()));
            }

            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private Pageable buildPageable(int page, int pageSize, String sortValue, Set<String> allowedSortFields, Sort defaultSort) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.max(pageSize, 1);
        return PageRequest.of(safePage - 1, safePageSize, parseSort(sortValue, allowedSortFields, defaultSort));
    }

    private Sort parseSort(String sortValue, Set<String> allowedSortFields, Sort defaultSort) {
        if (sortValue == null || sortValue.isBlank()) {
            return defaultSort;
        }
        String[] parts = sortValue.split(",");
        String property = parts[0].trim();
        if (!allowedSortFields.contains(property)) {
            return defaultSort;
        }
        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim())) {
            direction = Sort.Direction.DESC;
        }
        return Sort.by(direction, property);
    }

    private String toSortExpression(Sort sort) {
        Sort.Order order = sort.stream().findFirst()
                .orElse(Sort.Order.by("createdAt").with(Sort.Direction.DESC));
        return order.getProperty() + "," + order.getDirection().name().toLowerCase();
    }

    private TaskResponse toTaskResponse(Task task, boolean redactSensitiveFields) {
        return TaskResponse.builder()
                .id(task.getId())
                .userId(task.getUserId())
                .spaceId(task.getSpaceId())
                .researchProjectId(task.getResearchProjectId())
                .taskType(task.getTaskType())
                .targetType(task.getTargetType())
                .targetId(task.getTargetId())
                .taskStatus(task.getTaskStatus())
                .cancelRequested(task.isCancelRequested())
                .retryCount(task.getRetryCount())
                .maxRetryCount(task.getMaxRetryCount())
                .idempotencyKey(task.getIdempotencyKey())
                .errorMessage(redactSensitiveFields ? redactErrorMessage(task.getErrorMessage()) : task.getErrorMessage())
                .input(redactSensitiveFields ? redactJson(task.getInputJson()) : readJson(task.getInputJson()))
                .output(redactSensitiveFields ? redactJson(task.getOutputJson()) : readJson(task.getOutputJson()))
                .resultRefType(task.getResultRefType())
                .resultRefId(task.getResultRefId())
                .startedAt(task.getStartedAt())
                .finishedAt(task.getFinishedAt())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .build();
    }

    private TaskEventResponse toTaskEventResponse(TaskEvent event) {
        return TaskEventResponse.builder()
                .id(event.getId())
                .taskId(event.getTaskId())
                .eventType(event.getEventType())
                .fromStatus(event.getFromStatus())
                .toStatus(event.getToStatus())
                .message(event.getMessage())
                .payload(readJson(event.getPayloadJson()))
                .createdBy(event.getCreatedBy())
                .createdAt(event.getCreatedAt())
                .build();
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize task payload", e);
        }
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize task payload", e);
        }
    }

    private String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private boolean shouldRedactSensitiveFields(CurrentUser currentUser) {
        return currentUser.systemRole() == UserSystemRole.ADMIN;
    }

    private String redactErrorMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return null;
        }
        return REDACTED_MESSAGE;
    }

    private JsonNode redactedJsonNode() {
        return objectMapper.createObjectNode().put("redacted", true);
    }

    private JsonNode redactJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return redactedJsonNode();
    }
}
