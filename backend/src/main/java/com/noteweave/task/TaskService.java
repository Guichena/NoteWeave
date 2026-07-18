package com.noteweave.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.Json;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.security.AuditActorProvider;
import com.noteweave.security.SystemActor;
import com.noteweave.quota.WorkloadQuotaService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class TaskService implements TaskCommandPort {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final AuditActorProvider auditActorProvider;
    private final WorkloadQuotaService workloadQuotaService;

    public TaskService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this(jdbcTemplate, objectMapper, new SimpleMeterRegistry(), null, null);
    }

    public TaskService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this(jdbcTemplate, objectMapper, meterRegistry, null, null);
    }

    @Autowired
    public TaskService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            AuditActorProvider auditActorProvider,
            WorkloadQuotaService workloadQuotaService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.auditActorProvider = auditActorProvider;
        this.workloadQuotaService = workloadQuotaService;
    }

    @Transactional
    public String createTask(String workspaceId, String taskType, String targetType, String targetId, String phase, String message) {
        String taskId = Ids.newId();
        String actor = actor();
        acquireWorkloadQuota(workspaceId, taskType, taskId);
        releaseLeaseOnRollback(workspaceId, taskType, taskId);
        jdbcTemplate.update("""
                insert into task(
                    id, workspace_id, task_type, task_status, target_type, target_id, progress_phase, progress_message,
                    created_by, updated_by
                ) values (?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?)
                """, taskId, workspaceId, taskType, targetType, targetId, phase, message, actor, actor);
        jdbcTemplate.update("""
                insert into task_event(id, task_id, event_type, message)
                values (?, ?, 'TASK_CREATED', ?)
                """, Ids.newId(), taskId, message);
        return taskId;
    }

    private String actor() {
        return auditActorProvider == null
                ? SystemActor.of("TASK")
                : auditActorProvider.currentOrSystem("TASK");
    }

    private void acquireWorkloadQuota(String workspaceId, String taskType, String taskId) {
        String workload = workload(taskType);
        if (workloadQuotaService == null || workload == null) {
            return;
        }
        workloadQuotaService.requireRate(workspaceId, workload);
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
        return jdbcTemplate.query("""
                select id, workspace_id, task_type from task where id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("TASK_NOT_FOUND", "任务不存在");
            }
            String taskType = rs.getString("task_type");
            return new TaskQuotaRef(
                    rs.getString("id"),
                    rs.getString("workspace_id"),
                    taskType,
                    workload(taskType));
        }, taskId);
    }

    private TaskQuotaRef requireActiveTaskQuota(String taskId, String operation) {
        requireActiveTask(taskId, operation);
        return loadTaskQuota(taskId);
    }

    private String workload(String taskType) {
        return switch (taskType == null ? "" : taskType) {
            case "ARTIFACT_JOB" -> "artifact";
            case "RESEARCH_RUN" -> "research";
            default -> null;
        };
    }

    @Transactional
    public void startTask(String taskId) {
        String actor = actor();
        int updated = jdbcTemplate.update("""
                update task
                set task_status = 'RUNNING', updated_by = ?, updated_at = current_timestamp
                where id = ? and task_status = 'PENDING'
                """, actor, taskId);
        if (updated == 0) {
            handleNoTransition(taskId, "RUNNING");
            return;
        }
        jdbcTemplate.update("""
                insert into task_event(id, task_id, event_type, message)
                values (?, ?, 'TASK_RUNNING', '任务已被消费者接管')
                """, Ids.newId(), taskId);
    }

    @Transactional
    public void cancelTask(String taskId, String phase, String message, String resultRef) {
        String actor = actor();
        int updated = jdbcTemplate.update("""
                update task
                set task_status = 'CANCELLED', progress_phase = ?, progress_message = ?, result_ref = ?,
                    updated_by = ?, updated_at = current_timestamp
                where id = ? and task_status in ('PENDING', 'RUNNING', 'WAITING')
                """, phase, message, resultRef, actor, taskId);
        if (updated == 0) {
            handleNoTransition(taskId, "CANCELLED");
            return;
        }
        jdbcTemplate.update("""
                insert into task_event(id, task_id, event_type, message)
                values (?, ?, 'TASK_CANCELLED', ?)
                """, Ids.newId(), taskId, message);
        releaseLeaseAfterCommit(taskId);
    }

    @Transactional
    public void completeTask(String taskId, String phase, String message, String resultRef) {
        String actor = actor();
        int updated = jdbcTemplate.update("""
                update task
                set task_status = 'COMPLETED', progress_phase = ?, progress_message = ?, result_ref = ?,
                    updated_by = ?, updated_at = current_timestamp
                where id = ? and task_status in ('RUNNING', 'WAITING')
                """, phase, message, resultRef, actor, taskId);
        if (updated == 0) {
            handleNoTransition(taskId, "COMPLETED");
            return;
        }
        jdbcTemplate.update("""
                insert into task_event(id, task_id, event_type, message)
                values (?, ?, 'TASK_COMPLETED', ?)
                """, Ids.newId(), taskId, message);
        releaseLeaseAfterCommit(taskId);
    }

    @Transactional
    public void recordHeartbeat(String taskId, String phase, String message, Map<String, Object> payload) {
        TaskQuotaRef quota = requireActiveTaskQuota(taskId, "HEARTBEAT");
        renewLease(quota);
        String actor = actor();
        if (phase != null && !phase.isBlank()) {
            jdbcTemplate.update("""
                    update task
                    set progress_phase = ?, updated_by = ?, updated_at = current_timestamp
                    where id = ? and task_status in ('PENDING', 'RUNNING', 'WAITING')
                    """, phase, actor, taskId);
        }
        jdbcTemplate.update("""
                insert into task_event(id, task_id, event_type, message, payload_json)
                values (?, ?, 'TASK_HEARTBEAT', ?, ?)
                """, Ids.newId(), taskId, message, Json.write(objectMapper, payload));
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
        int updated = jdbcTemplate.update("""
                update task
                set task_status = 'RUNNING',
                    progress_phase = ?,
                    progress_message = ?,
                    updated_by = ?,
                    updated_at = current_timestamp
                where id = ? and task_status in ('RUNNING', 'WAITING')
                """, phase, message, actor, taskId);
        if (updated == 0) {
            throwInvalidTransition(taskId, "RUNNING");
        }
        jdbcTemplate.update("""
                insert into task_event(id, task_id, event_type, message, payload_json)
                values (?, ?, 'TASK_PROGRESS', ?, ?)
                """, Ids.newId(), taskId, message, Json.write(objectMapper, buildProgressPayload(
                phase,
                progressPercent,
                metrics,
                payload
        )));
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
        int updated = jdbcTemplate.update("""
                update task
                set task_status = 'WAITING',
                    progress_phase = ?,
                    progress_message = ?,
                    updated_by = ?,
                    updated_at = current_timestamp
                where id = ? and task_status = 'RUNNING'
                """, phase, message, actor, taskId);
        if (updated == 0) {
            String currentStatus = currentTaskStatus(taskId);
            if (!"WAITING".equals(currentStatus)) {
                throw transitionException(currentStatus, "WAITING");
            }
            return;
        }
        jdbcTemplate.update("""
                insert into task_event(id, task_id, event_type, message, payload_json)
                values (?, ?, 'TASK_PROGRESS', ?, ?)
                """, Ids.newId(), taskId, message, Json.write(objectMapper, buildProgressPayload(
                phase,
                progressPercent,
                metrics,
                payload
        )));
    }

    @Transactional
    public void failTask(String taskId, String phase, String message, String errorCode, boolean retryable) {
        String actor = actor();
        int updated = jdbcTemplate.update("""
                update task
                set task_status = 'FAILED',
                    progress_phase = ?,
                    progress_message = ?,
                    error_message = ?,
                    updated_by = ?,
                    updated_at = current_timestamp
                where id = ? and task_status in ('PENDING', 'RUNNING', 'WAITING')
                """, phase, message, errorCode + ": " + message, actor, taskId);
        if (updated == 0) {
            handleNoTransition(taskId, "FAILED");
            return;
        }
        jdbcTemplate.update("""
                insert into task_event(id, task_id, event_type, message, payload_json)
                values (?, ?, 'TASK_FAILED', ?, ?)
                """, Ids.newId(), taskId, message, Json.write(objectMapper, Map.of(
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
        int updated = jdbcTemplate.update("""
                update task set task_status = ?, progress_phase = 'REDRIVEN',
                    progress_message = 'Outbox 死信已人工重驱', error_message = null,
                    updated_by = ?, updated_at = current_timestamp
                where id = ? and task_status = 'FAILED'
                """, targetStatus, actor, taskId);
        if (updated == 0) {
            throw transitionException(currentTaskStatus(taskId), targetStatus);
        }
        jdbcTemplate.update("""
                insert into task_event(id, task_id, event_type, message)
                values (?, ?, 'TASK_REDRIVEN', 'Outbox 死信已人工重驱')
                """, Ids.newId(), taskId);
    }

    public TaskResponse getTask(String taskId) {
        return jdbcTemplate.query("""
                select id, task_type, task_status, progress_phase, progress_message, result_ref, error_message, target_type, target_id
                from task where id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("TASK_NOT_FOUND", "任务不存在");
            }
            return new TaskResponse(
                    rs.getString("id"),
                    rs.getString("task_type"),
                    rs.getString("task_status"),
                    rs.getString("progress_phase"),
                    rs.getString("progress_message"),
                    rs.getString("result_ref") == null ? "" : rs.getString("result_ref"),
                    rs.getString("error_message") == null ? "" : rs.getString("error_message"),
                    rs.getString("target_type") == null ? "" : rs.getString("target_type"),
                    rs.getString("target_id") == null ? "" : rs.getString("target_id"),
                    loadWaitContext(
                            rs.getString("id"),
                            rs.getString("task_status"),
                            rs.getString("progress_phase")
                    )
            );
        }, taskId);
    }

    public WaitContextResponse loadWaitContext(String taskId, String taskStatus, String progressPhase) {
        if (!isWaitingStatus(taskStatus, progressPhase)) {
            return null;
        }
        return jdbcTemplate.query("""
                select payload_json
                from task_event
                where task_id = ? and event_type = 'TASK_PROGRESS'
                order by created_at desc, id desc
                limit 1
                """, rs -> {
            if (!rs.next()) {
                return new WaitContextResponse(
                        blankIfNull(progressPhase),
                        emptyProviderJob(),
                        emptyApprovalRequest(),
                        emptyWaitReason()
                );
            }
            Map<String, Object> payload = readPayloadMap(rs.getString("payload_json"));
            return new WaitContextResponse(
                    blankIfNull(progressPhase),
                    toWaitProviderJob(payload.get("provider_job")),
                    toWaitApprovalRequest(payload.get("approval_request")),
                    toWaitReason(payload.get("wait_reason"))
            );
        }, taskId);
    }

    public TaskRef getTaskRef(String taskId) {
        return jdbcTemplate.query("""
                select id, workspace_id, task_type, task_status, target_type, target_id
                from task where id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("TASK_NOT_FOUND", "任务不存在");
            }
            return new TaskRef(
                    rs.getString("id"),
                    rs.getString("workspace_id"),
                    rs.getString("task_type"),
                    rs.getString("task_status"),
                    rs.getString("target_type"),
                    rs.getString("target_id")
            );
        }, taskId);
    }

    public String streamEvents(String taskId) {
        List<TaskEventResponse> events = listEvents(taskId);
        StringBuilder builder = new StringBuilder();
        for (TaskEventResponse event : events) {
            builder.append("id: ").append(event.eventId()).append("\n");
            builder.append("event: ").append(toSseEventName(event.eventType())).append("\n");
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("message", event.message());
            payload.put("payload", readPayloadMap(event.payloadJson()));
            payload.put("event_type", event.eventType());
            payload.put("created_at", event.createdAt().toString());
            builder.append("data: ").append(escape(Json.write(objectMapper, payload))).append("\n\n");
        }
        return builder.toString();
    }

    public List<TaskEventResponse> listEvents(String taskId) {
        getTask(taskId);
        return jdbcTemplate.query("""
                select id, event_type, message, payload_json, created_at
                from task_event
                where task_id = ?
                order by created_at asc, id asc
                """, (rs, rowNum) -> new TaskEventResponse(
                rs.getString("id"),
                rs.getString("event_type"),
                rs.getString("message") == null ? "" : rs.getString("message"),
                rs.getString("payload_json"),
                toInstant(rs.getTimestamp("created_at"))
        ), taskId);
    }

    private String toSseEventName(String eventType) {
        return switch (eventType) {
            case "TASK_CREATED" -> "task.status";
            case "TASK_COMPLETED" -> "task.completed";
            case "TASK_FAILED" -> "task.failed";
            case "TASK_HEARTBEAT" -> "task.heartbeat";
            default -> "task.progress";
        };
    }

    private String escape(String data) {
        return data.replace("\r", "").replace("\n", "\\n");
    }

    private Instant toInstant(Timestamp value) {
        return value == null ? Instant.now() : value.toInstant();
    }

    private Map<String, Object> readPayloadMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception ex) {
            throw new BusinessException("TASK_EVENT_PAYLOAD_PARSE_FAILED", "任务事件载荷解析失败");
        }
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
        return jdbcTemplate.query("""
                select task_status from task where id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("TASK_NOT_FOUND", "任务不存在");
            }
            return rs.getString("task_status");
        }, taskId);
    }

    private boolean isTerminalStatus(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private boolean isWaitingStatus(String taskStatus, String progressPhase) {
        return "WAITING".equalsIgnoreCase(blankIfNull(taskStatus))
                || blankIfNull(progressPhase).startsWith("WAITING_FOR_");
    }

    private record TaskQuotaRef(
            String taskId,
            String workspaceId,
            String taskType,
            String workload
    ) {
    }

    private Map<String, Object> nestedMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    normalized.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return normalized;
        }
        return Map.of();
    }

    private WaitProviderJobResponse toWaitProviderJob(Object value) {
        Map<String, Object> providerJob = nestedMap(value);
        return new WaitProviderJobResponse(
                readText(providerJob, "provider_id"),
                readText(providerJob, "server_id"),
                readText(providerJob, "tool_name"),
                readText(providerJob, "capability_name"),
                readText(providerJob, "operation_key"),
                readText(providerJob, "provider_status"),
                readText(providerJob, "health_status"),
                readText(providerJob, "provider_job_status"),
                readText(providerJob, "status"),
                readText(providerJob, "request_id"),
                readText(providerJob, "provider_job_id"),
                readText(providerJob, "provider_receipt_id"),
                readText(providerJob, "delivery_id"),
                readText(providerJob, "callback_token"),
                readText(providerJob, "adapter_callback_token"),
                readText(providerJob, "callback_status"),
                resolveDispatchCount(providerJob),
                resolvePreviousFailedDeliveryCount(providerJob),
                resolveHasPreviousFailedDelivery(providerJob),
                readProviderDeliveryAttempts(providerJob.get("provider_delivery_attempts"))
        );
    }

    private WaitApprovalRequestResponse toWaitApprovalRequest(Object value) {
        Map<String, Object> approvalRequest = nestedMap(value);
        return new WaitApprovalRequestResponse(
                readText(approvalRequest, "request_id"),
                readText(approvalRequest, "task_id"),
                readText(approvalRequest, "workspace_id"),
                readText(approvalRequest, "capability_name"),
                readText(approvalRequest, "provider_id"),
                readText(approvalRequest, "server_id"),
                readText(approvalRequest, "tool_name"),
                readText(approvalRequest, "status")
        );
    }

    private WaitReasonResponse toWaitReason(Object value) {
        Map<String, Object> waitReason = nestedMap(value);
        return new WaitReasonResponse(
                readText(waitReason, "status"),
                readText(waitReason, "provider_id"),
                readText(waitReason, "operation_key"),
                readText(waitReason, "capability_name"),
                readStringList(waitReason.get("unavailable_capabilities")),
                readStringList(waitReason.get("capabilities")),
                readBlockedOperations(waitReason.get("blocked_operations"))
        );
    }

    private List<String> readStringList(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (Object item : items) {
            if (item != null) {
                String text = String.valueOf(item).trim();
                if (!text.isEmpty()) {
                    normalized.add(text);
                }
            }
        }
        return List.copyOf(normalized);
    }

    private List<WaitBlockedOperationResponse> readBlockedOperations(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<WaitBlockedOperationResponse> operations = new ArrayList<>();
        for (Object item : items) {
            Map<String, Object> record = nestedMap(item);
            if (record.isEmpty()) {
                continue;
            }
            operations.add(new WaitBlockedOperationResponse(
                    readText(record, "request_id"),
                    readText(record, "source_id"),
                    readText(record, "operation_key"),
                    readText(record, "capability_name"),
                    readText(record, "callback_status")
            ));
        }
        return List.copyOf(operations);
    }

    private String readText(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private Integer readInteger(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer resolvePreviousFailedDeliveryCount(Map<String, Object> providerJob) {
        Integer explicitCount = readInteger(providerJob, "previous_failed_delivery_count");
        if (explicitCount != null) {
            return explicitCount;
        }
        List<Map<String, Object>> deliveryAttempts = nestedMapList(providerJob.get("provider_delivery_attempts"));
        if (deliveryAttempts.isEmpty()) {
            return null;
        }
        int failedAttempts = (int) deliveryAttempts.stream()
                .map(attempt -> readText(attempt, "ack_status"))
                .filter(status -> "FAILED".equalsIgnoreCase(status))
                .count();
        return failedAttempts;
    }

    private Integer resolveDispatchCount(Map<String, Object> providerJob) {
        Integer explicitCount = readInteger(providerJob, "dispatch_count");
        if (explicitCount != null) {
            return explicitCount;
        }
        List<Map<String, Object>> deliveryAttempts = nestedMapList(providerJob.get("provider_delivery_attempts"));
        if (deliveryAttempts.isEmpty()) {
            return null;
        }
        return deliveryAttempts.stream()
                .map(attempt -> readInteger(attempt, "dispatch_count"))
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(null);
    }

    private Boolean resolveHasPreviousFailedDelivery(Map<String, Object> providerJob) {
        Object explicitValue = providerJob.get("has_previous_failed_delivery");
        if (explicitValue instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (explicitValue instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text.trim());
        }
        Integer failedCount = resolvePreviousFailedDeliveryCount(providerJob);
        if (failedCount == null) {
            return null;
        }
        return failedCount > 0;
    }

    private List<WaitProviderDeliveryAttemptResponse> readProviderDeliveryAttempts(Object value) {
        List<Map<String, Object>> deliveryAttempts = nestedMapList(value);
        if (deliveryAttempts.isEmpty()) {
            return List.of();
        }
        List<WaitProviderDeliveryAttemptResponse> attempts = new ArrayList<>();
        for (Map<String, Object> attempt : deliveryAttempts) {
            attempts.add(new WaitProviderDeliveryAttemptResponse(
                    readText(attempt, "delivery_id"),
                    readInteger(attempt, "dispatch_count"),
                    readText(attempt, "callback_token"),
                    readText(attempt, "dispatched_at"),
                    readText(attempt, "callback_deadline_at"),
                    readText(attempt, "provider_job_id"),
                    readText(attempt, "provider_receipt_id"),
                    readText(attempt, "input_digest"),
                    readText(attempt, "ack_status"),
                    readText(attempt, "callback_received_at"),
                    readText(attempt, "result_locator"),
                    readText(attempt, "error_code"),
                    readText(attempt, "error_message")
            ));
        }
        return List.copyOf(attempts);
    }

    private List<Map<String, Object>> nestedMapList(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object item : items) {
            Map<String, Object> nested = nestedMap(item);
            if (!nested.isEmpty()) {
                maps.add(nested);
            }
        }
        return List.copyOf(maps);
    }

    private WaitProviderJobResponse emptyProviderJob() {
        return new WaitProviderJobResponse(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, List.of()
        );
    }

    private WaitApprovalRequestResponse emptyApprovalRequest() {
        return new WaitApprovalRequestResponse(null, null, null, null, null, null, null, null);
    }

    private WaitReasonResponse emptyWaitReason() {
        return new WaitReasonResponse(null, null, null, null, List.of(), List.of(), List.of());
    }

    private String blankIfNull(String value) {
        return value == null ? "" : value;
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
}
