package com.noteweave.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.Json;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.security.AuditActorProvider;
import com.noteweave.security.SystemActor;
import com.noteweave.quota.WorkloadQuotaService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class TaskService implements TaskCommandPort {

    private static final int DEFAULT_EVENT_HISTORY_LIMIT = 200;
    private static final int MAX_EVENT_HISTORY_LIMIT = 500;

    private final ObjectMapper objectMapper;
    private final TaskReadRepository taskReadRepository;
    private final TaskEventRepository taskEventRepository;
    private final TaskStateRepository taskStateRepository;
    private final TaskWaitContextAssembler waitContextAssembler;
    private final MeterRegistry meterRegistry;
    private final AuditActorProvider auditActorProvider;
    private final WorkloadQuotaService workloadQuotaService;
    private final ApplicationEventPublisher eventPublisher;

    public TaskService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this(jdbcTemplate, objectMapper, new SimpleMeterRegistry(), null, null, null);
    }

    public TaskService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this(jdbcTemplate, objectMapper, meterRegistry, null, null, null);
    }

    public TaskService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            AuditActorProvider auditActorProvider,
            WorkloadQuotaService workloadQuotaService
    ) {
        this(jdbcTemplate, objectMapper, meterRegistry, auditActorProvider, workloadQuotaService, null,
                new TaskReadRepository(jdbcTemplate));
    }

    public TaskService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            AuditActorProvider auditActorProvider,
            WorkloadQuotaService workloadQuotaService,
            ApplicationEventPublisher eventPublisher
    ) {
        this(jdbcTemplate, objectMapper, meterRegistry, auditActorProvider, workloadQuotaService,
                eventPublisher, new TaskReadRepository(jdbcTemplate));
    }

    public TaskService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            AuditActorProvider auditActorProvider,
            WorkloadQuotaService workloadQuotaService,
            ApplicationEventPublisher eventPublisher,
            TaskReadRepository taskReadRepository
    ) {
        this(jdbcTemplate, objectMapper, meterRegistry, auditActorProvider, workloadQuotaService,
                eventPublisher, taskReadRepository, new TaskEventRepository(jdbcTemplate),
                new TaskStateRepository(jdbcTemplate));
    }

    public TaskService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            AuditActorProvider auditActorProvider,
            WorkloadQuotaService workloadQuotaService,
            ApplicationEventPublisher eventPublisher,
            TaskReadRepository taskReadRepository,
            TaskEventRepository taskEventRepository
    ) {
        this(jdbcTemplate, objectMapper, meterRegistry, auditActorProvider, workloadQuotaService,
                eventPublisher, taskReadRepository, taskEventRepository, new TaskStateRepository(jdbcTemplate));
    }

    @Autowired
    public TaskService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            AuditActorProvider auditActorProvider,
            WorkloadQuotaService workloadQuotaService,
            ApplicationEventPublisher eventPublisher,
            TaskReadRepository taskReadRepository,
            TaskEventRepository taskEventRepository,
            TaskStateRepository taskStateRepository
    ) {
        this.objectMapper = objectMapper;
        this.taskReadRepository = taskReadRepository;
        this.taskEventRepository = taskEventRepository;
        this.taskStateRepository = taskStateRepository;
        this.waitContextAssembler = new TaskWaitContextAssembler(objectMapper);
        this.meterRegistry = meterRegistry;
        this.auditActorProvider = auditActorProvider;
        this.workloadQuotaService = workloadQuotaService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public String createTask(String workspaceId, String taskType, String targetType, String targetId, String phase, String message) {
        return createTaskInternal(workspaceId, taskType, targetType, targetId, phase, message, null);
    }

    /**
     * Creates a task from a background coordinator acting for an already verified user: there is no
     * request session, so the workload rate is charged to that user explicitly.
     */
    @Transactional
    public String createTaskForActor(String workspaceId, String taskType, String targetType, String targetId,
                                     String phase, String message, String actorUserId) {
        if (actorUserId == null || actorUserId.isBlank()) {
            throw new IllegalArgumentException("Task actor is required");
        }
        return createTaskInternal(workspaceId, taskType, targetType, targetId, phase, message, actorUserId);
    }

    private String createTaskInternal(String workspaceId, String taskType, String targetType, String targetId,
                                      String phase, String message, String actorUserId) {
        String taskId = Ids.newId();
        String actor = actor();
        acquireWorkloadQuota(workspaceId, taskType, taskId, actorUserId);
        releaseLeaseOnRollback(workspaceId, taskType, taskId);
        taskStateRepository.insertTask(taskId, workspaceId, taskType, targetType, targetId, phase, message, actor);
        taskEventRepository.append(taskId, "TASK_CREATED", message);
        return taskId;
    }

    private String actor() {
        return auditActorProvider == null
                ? SystemActor.of("TASK")
                : auditActorProvider.currentOrSystem("TASK");
    }

    private void acquireWorkloadQuota(String workspaceId, String taskType, String taskId, String actorUserId) {
        String workload = workload(taskType);
        if (workloadQuotaService == null || workload == null) {
            return;
        }
        if (actorUserId == null) {
            workloadQuotaService.requireRate(workspaceId, workload);
        } else {
            workloadQuotaService.requireRateFor(workspaceId, workload, actorUserId);
        }
        workloadQuotaService.acquireLease(workspaceId, workload, taskId);
    }

    private void releaseLeaseOnRollback(String workspaceId, String taskType, String taskId) {
        String workload = workload(taskType);
        if (workloadQuotaService == null || workload == null
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    workloadQuotaService.releaseLease(workspaceId, workload, taskId);
                }
            }
        });
    }

    private void releaseLeaseAfterCommit(String taskId) {
        TaskQuotaRef quota = loadTaskQuota(taskId);
        if (workloadQuotaService == null || quota.workload() == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            releaseLease(quota, taskId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                releaseLease(quota, taskId);
            }
        });
    }

    private void renewLease(TaskQuotaRef quota) {
        if (workloadQuotaService != null && quota.workload() != null) {
            workloadQuotaService.renewLease(quota.workspaceId(), quota.workload(), quota.taskId());
        }
    }

    private void releaseLease(TaskQuotaRef quota, String taskId) {
        if (workloadQuotaService != null && quota.workload() != null) {
            workloadQuotaService.releaseLease(quota.workspaceId(), quota.workload(), taskId);
        }
    }

    private TaskQuotaRef loadTaskQuota(String taskId) {
        TaskQuotaRow row = taskReadRepository.loadTaskQuota(taskId);
        return new TaskQuotaRef(row.id(), row.workspaceId(), row.taskType(), workload(row.taskType()));
    }

    private TaskQuotaRef requireActiveTaskQuota(String taskId, String operation) {
        requireActiveTask(taskId, operation);
        return loadTaskQuota(taskId);
    }

    private String workload(String taskType) {
        return switch (taskType == null ? "" : taskType) {
            case "ARTIFACT_JOB", "VIDEO_MATERIAL" -> "artifact";
            case "RESEARCH_RUN" -> "research";
            default -> null;
        };
    }

    @Transactional
    public void startTask(String taskId) {
        String actor = actor();
        int updated = taskStateRepository.start(taskId, actor);
        if (updated == 0) {
            handleNoTransition(taskId, "RUNNING");
            return;
        }
        taskEventRepository.append(taskId, "TASK_RUNNING", "任务已被消费者接管");
    }

    @Transactional
    public void cancelTask(String taskId, String phase, String message, String resultRef) {
        String actor = actor();
        int updated = taskStateRepository.cancel(taskId, phase, message, resultRef, actor);
        if (updated == 0) {
            handleNoTransition(taskId, "CANCELLED");
            return;
        }
        taskEventRepository.append(taskId, "TASK_CANCELLED", message);
        releaseLeaseAfterCommit(taskId);
    }

    @Transactional
    public void completeTask(String taskId, String phase, String message, String resultRef) {
        String actor = actor();
        int updated = taskStateRepository.complete(taskId, phase, message, resultRef, actor);
        if (updated == 0) {
            handleNoTransition(taskId, "COMPLETED");
            return;
        }
        taskEventRepository.append(taskId, "TASK_COMPLETED", message);
        releaseLeaseAfterCommit(taskId);
    }

    @Override
    public void recordStage(String taskId, String phase, String message) {
        if (taskId == null || !"RUNNING".equals(currentTaskStatus(taskId))) {
            return;
        }
        recordProgress(taskId, phase, message, null, null, null);
    }

    @Transactional
    public void recordHeartbeat(String taskId, String phase, String message, Map<String, Object> payload) {
        TaskQuotaRef quota = requireActiveTaskQuota(taskId, "HEARTBEAT");
        renewLease(quota);
        String actor = actor();
        if (phase != null && !phase.isBlank()) {
            taskStateRepository.updateHeartbeatPhase(taskId, phase, actor);
        }
        taskEventRepository.append(taskId, "TASK_HEARTBEAT", message, Json.write(objectMapper, payload));
    }

    @Transactional
    public void recordProgress(
            String taskId,
            String phase,
            String message,
            Integer progressPercent,
            Map<String, Object> metrics,
            Map<String, Object> payload
    ) {
        renewLease(requireActiveTaskQuota(taskId, "RUNNING"));
        String actor = actor();
        int updated = taskStateRepository.recordProgress(taskId, phase, message, actor);
        if (updated == 0) {
            throwInvalidTransition(taskId, "RUNNING");
        }
        taskEventRepository.append(taskId, "TASK_PROGRESS", message, Json.write(objectMapper, buildProgressPayload(
                phase,
                progressPercent,
                metrics,
                payload
        )));
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new TaskProgressRecordedEvent(
                    taskId, phase, message, progressPercent, metrics, payload
            ));
        }
    }

    @Transactional
    public void recordWaiting(
            String taskId,
            String phase,
            String message,
            Integer progressPercent,
            Map<String, Object> metrics,
            Map<String, Object> payload
    ) {
        renewLease(requireActiveTaskQuota(taskId, "WAITING"));
        String actor = actor();
        int updated = taskStateRepository.recordWaiting(taskId, phase, message, actor);
        if (updated == 0) {
            String currentStatus = currentTaskStatus(taskId);
            if (!"WAITING".equals(currentStatus)) {
                throw transitionException(currentStatus, "WAITING");
            }
            return;
        }
        taskEventRepository.append(taskId, "TASK_PROGRESS", message, Json.write(objectMapper, buildProgressPayload(
                phase,
                progressPercent,
                metrics,
                payload
        )));
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new TaskProgressRecordedEvent(
                    taskId, phase, message, progressPercent, metrics, payload
            ));
        }
    }

    @Transactional
    public void failTask(String taskId, String phase, String message, String errorCode, boolean retryable) {
        String actor = actor();
        int updated = taskStateRepository.fail(taskId, phase, message, errorCode, actor);
        if (updated == 0) {
            handleNoTransition(taskId, "FAILED");
            return;
        }
        taskEventRepository.append(taskId, "TASK_FAILED", message, Json.write(objectMapper, Map.of(
                "error_code", errorCode,
                "retryable", retryable
        )));
        releaseLeaseAfterCommit(taskId);
    }

    @Transactional
    public void redriveTask(String taskId, boolean resumeRunning) {
        TaskQuotaRef quota = loadTaskQuota(taskId);
        if (workloadQuotaService != null && quota.workload() != null) {
            workloadQuotaService.requireRate(quota.workspaceId(), quota.workload());
            workloadQuotaService.acquireLease(quota.workspaceId(), quota.workload(), taskId);
            releaseLeaseOnRollback(quota.workspaceId(), quota.taskType(), taskId);
        }
        String targetStatus = resumeRunning ? "RUNNING" : "PENDING";
        String actor = actor();
        String redriveMessage = "Outbox 死信已人工重驱";
        int updated = taskStateRepository.redrive(taskId, targetStatus, redriveMessage, actor);
        if (updated == 0) {
            throw transitionException(currentTaskStatus(taskId), targetStatus);
        }
        taskEventRepository.append(taskId, "TASK_REDRIVEN", redriveMessage);
    }

    public TaskResponse getTask(String taskId) {
        TaskRecord row = taskReadRepository.getTask(taskId);
        return new TaskResponse(
                row.id(), row.taskType(), row.taskStatus(), row.progressPhase(), row.progressMessage(),
                row.resultRef(), row.errorMessage(), row.targetType(), row.targetId(),
                loadWaitContext(row.id(), row.taskStatus(), row.progressPhase())
        );
    }

    public WaitContextResponse loadWaitContext(String taskId, String taskStatus, String progressPhase) {
        if (!isWaitingStatus(taskStatus, progressPhase)) {
            return null;
        }
        return waitContextAssembler.build(
                progressPhase, taskReadRepository.loadLatestProgressPayload(taskId));
    }

    public Map<String, WaitContextResponse> loadWaitContexts(Map<String, String> taskStatuses) {
        if (taskStatuses == null || taskStatuses.isEmpty()) {
            return Map.of();
        }
        List<String> waitingTaskIds = taskStatuses.entrySet().stream()
                .filter(entry -> isWaitingStatus(
                        "WAITING".equalsIgnoreCase(blankIfNull(entry.getValue())) ? "WAITING" : "",
                        blankIfNull(entry.getValue())
                ))
                .map(Map.Entry::getKey)
                .toList();
        if (waitingTaskIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> payloads = taskReadRepository.loadLatestProgressPayloads(waitingTaskIds);
        return waitContextAssembler.buildAll(taskStatuses, waitingTaskIds, payloads);
    }

    public TaskRef getTaskRef(String taskId) {
        return loadTaskRef(taskId, false);
    }

    public TaskRef lockTaskRef(String taskId) {
        return loadTaskRef(taskId, true);
    }

    private TaskRef loadTaskRef(String taskId, boolean forUpdate) {
        return taskReadRepository.loadTaskRef(taskId, forUpdate);
    }

    public List<TaskEventResponse> listEvents(String taskId) {
        return listEvents(taskId, null, DEFAULT_EVENT_HISTORY_LIMIT);
    }

    public List<TaskEventResponse> listEvents(String taskId, String afterEventId) {
        return listEvents(taskId, afterEventId, DEFAULT_EVENT_HISTORY_LIMIT);
    }

    public List<TaskEventResponse> listEvents(String taskId, String afterEventId, Integer limit) {
        getTask(taskId);
        int boundedLimit = normalizeEventHistoryLimit(limit);
        return taskReadRepository.listEvents(taskId, afterEventId, boundedLimit);
    }

    List<TaskEventResponse> listEventsForAuthorizedStream(String taskId, String afterEventId) {
        return taskReadRepository.listEvents(taskId, afterEventId, DEFAULT_EVENT_HISTORY_LIMIT);
    }

    TaskStreamPoll pollAuthorizedStream(String taskId, String afterEventId) {
        return taskReadRepository.poll(taskId, afterEventId);
    }

    String currentStatusForAuthorizedStream(String taskId) {
        return taskReadRepository.currentStatus(taskId);
    }

    private int normalizeEventHistoryLimit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return DEFAULT_EVENT_HISTORY_LIMIT;
        }
        if (requestedLimit <= 0) {
            throw new BusinessException("TASK_EVENT_LIMIT_INVALID", "任务事件查询数量必须大于 0");
        }
        return Math.min(requestedLimit, MAX_EVENT_HISTORY_LIMIT);
    }

    private void ensureTaskExists(String taskId) {
        getTaskRef(taskId);
    }

    private void requireActiveTask(String taskId, String operation) {
        String status = currentTaskStatus(taskId);
        if (isTerminalStatus(status)) {
            invalidTransition(status, operation);
            throw new BusinessException(
                    "TASK_STATE_TRANSITION_INVALID",
                    "任务已处于终态 " + status + "，不能执行 " + operation
            );
        }
    }

    private void handleNoTransition(String taskId, String targetStatus) {
        String currentStatus = currentTaskStatus(taskId);
        if (targetStatus.equals(currentStatus)) {
            return;
        }
        throw transitionException(currentStatus, targetStatus);
    }

    private void throwInvalidTransition(String taskId, String targetStatus) {
        throw transitionException(currentTaskStatus(taskId), targetStatus);
    }

    private BusinessException transitionException(String currentStatus, String targetStatus) {
        invalidTransition(currentStatus, targetStatus);
        return new BusinessException(
                "TASK_STATE_TRANSITION_INVALID",
                "任务状态不能从 " + currentStatus + " 迁移到 " + targetStatus
        );
    }

    private void invalidTransition(String from, String to) {
        meterRegistry.counter("noteweave.task.invalid_transition", "from", from == null ? "UNKNOWN" : from,
                "to", to == null ? "UNKNOWN" : to).increment();
    }

    private String currentTaskStatus(String taskId) {
        return taskReadRepository.currentStatus(taskId);
    }

    private boolean isTerminalStatus(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private boolean isWaitingStatus(String taskStatus, String progressPhase) {
        return "WAITING".equalsIgnoreCase(blankIfNull(taskStatus))
                || blankIfNull(progressPhase).startsWith("WAITING_FOR_");
    }

    private String blankIfNull(String value) {
        return value == null ? "" : value;
    }

    private record TaskQuotaRef(
            String taskId,
            String workspaceId,
            String taskType,
            String workload
    ) {
    }

    private Map<String, Object> buildProgressPayload(
            String phase,
            Integer progressPercent,
            Map<String, Object> metrics,
            Map<String, Object> payload
    ) {
        LinkedHashMap<String, Object> progressPayload = new LinkedHashMap<>();
        progressPayload.put("phase", phase == null ? "" : phase);
        progressPayload.put("progress_percent", progressPercent == null ? 0 : progressPercent);
        progressPayload.put("metrics", metrics == null ? Map.of() : metrics);
        if (payload != null && !payload.isEmpty()) {
            progressPayload.putAll(payload);
        }
        return progressPayload;
    }

    public record TaskRef(
            String taskId,
            String workspaceId,
            String taskType,
            String taskStatus,
            String targetType,
            String targetId
    ) {
    }

    public record TaskStreamPoll(List<TaskEventResponse> events, String currentStatus) {
    }
}
