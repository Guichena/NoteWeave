package com.noteweave.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.artifact.ArtifactJobService;
import com.noteweave.artifact.ArtifactExportService;
import com.noteweave.common.BusinessException;
import com.noteweave.infra.outbox.DurableOutboxDispatcher;
import com.noteweave.infra.outbox.OutboxDispatchPolicy;
import com.noteweave.task.TaskService;
import java.util.Map;
import java.util.List;
import java.util.TreeMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import com.noteweave.common.Ids;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;

@Service
public class WorkerTaskCallbackService {

    private static final String ARTIFACT_TOPIC = "noteweave.artifact.job";

    private final TaskService taskService;
    private final ArtifactJobService artifactJobService;
    private final ArtifactExportService artifactExportService;
    private final JdbcTemplate jdbcTemplate;
    private final DurableOutboxDispatcher outboxDispatcher;
    private final ObjectMapper objectMapper;

    public WorkerTaskCallbackService(
            TaskService taskService,
            ArtifactJobService artifactJobService,
            ArtifactExportService artifactExportService,
            JdbcTemplate jdbcTemplate
    ) {
        this(taskService, artifactJobService, artifactExportService, jdbcTemplate,
                new DurableOutboxDispatcher(jdbcTemplate, new SimpleMeterRegistry()),
                new ObjectMapper().findAndRegisterModules());
    }

    @Autowired
    public WorkerTaskCallbackService(
            TaskService taskService,
            ArtifactJobService artifactJobService,
            ArtifactExportService artifactExportService,
            JdbcTemplate jdbcTemplate,
            DurableOutboxDispatcher outboxDispatcher,
            ObjectMapper objectMapper
    ) {
        this.taskService = taskService;
        this.artifactJobService = artifactJobService;
        this.artifactExportService = artifactExportService;
        this.jdbcTemplate = jdbcTemplate;
        this.outboxDispatcher = outboxDispatcher;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public WorkerAckResponse heartbeat(String taskId, WorkerHeartbeatRequest request) {
        return heartbeat(taskId, request, "");
    }

    @Transactional
    public WorkerAckResponse heartbeat(String taskId, WorkerHeartbeatRequest request, String idempotencyKey) {
        return heartbeatInternal(taskId, request, idempotencyKey, "", false);
    }

    @Transactional
    public WorkerAckResponse heartbeatFromDelivery(
            String taskId,
            WorkerHeartbeatRequest request,
            String idempotencyKey,
            String deliveryToken
    ) {
        return heartbeatInternal(taskId, request, idempotencyKey, deliveryToken, true);
    }

    private WorkerAckResponse heartbeatInternal(
            String taskId,
            WorkerHeartbeatRequest request,
            String idempotencyKey,
            String deliveryToken,
            boolean requireDelivery
    ) {
        TaskService.TaskRef task = taskService.getTaskRef(taskId);
        if (isTerminalStatus(task.taskStatus())) {
            return terminalAck(taskId, task.taskStatus());
        }
        renewDelivery(taskId, deliveryToken, requireDelivery);
        validateCallbackOwnership(task);
        validateWorkerType(task, request.workerType());
        WorkerAckResponse duplicate = claimCallback(taskId, idempotencyKey, "HEARTBEAT", request);
        if (duplicate != null) return duplicate;
        if ("PENDING".equals(task.taskStatus())) {
            taskService.startTask(taskId);
            task = taskService.getTaskRef(taskId);
        }
        taskService.recordHeartbeat(taskId, request.phase(), request.workerType() + " heartbeat", Map.of(
                "worker_type", request.workerType(),
                "worker_instance_id", request.workerInstanceId(),
                "heartbeat_at", request.heartbeatAt()
        ));
        return new WorkerAckResponse(taskId, "HEARTBEAT_RECORDED", "");
    }

    @Transactional
    public WorkerAckResponse progress(String taskId, WorkerProgressRequest request) {
        return progress(taskId, request, "");
    }

    @Transactional
    public WorkerAckResponse progress(String taskId, WorkerProgressRequest request, String idempotencyKey) {
        return progressInternal(taskId, request, idempotencyKey, "", false);
    }

    @Transactional
    public WorkerAckResponse progressFromDelivery(
            String taskId,
            WorkerProgressRequest request,
            String idempotencyKey,
            String deliveryToken
    ) {
        return progressInternal(taskId, request, idempotencyKey, deliveryToken, true);
    }

    private WorkerAckResponse progressInternal(
            String taskId,
            WorkerProgressRequest request,
            String idempotencyKey,
            String deliveryToken,
            boolean requireDelivery
    ) {
        TaskService.TaskRef task = taskService.getTaskRef(taskId);
        if (isTerminalStatus(task.taskStatus())) {
            return terminalAck(taskId, task.taskStatus());
        }
        renewDelivery(taskId, deliveryToken, requireDelivery);
        validateCallbackOwnership(task);
        WorkerAckResponse duplicate = claimCallback(taskId, idempotencyKey, "PROGRESS", request);
        if (duplicate != null) return duplicate;
        if ("PENDING".equals(task.taskStatus())) {
            taskService.startTask(taskId);
            task = taskService.getTaskRef(taskId);
        }
        boolean waitingPhase = isWaitingPhase(request.phase());
        if (waitingPhase) {
            artifactJobService.markWaiting(taskId, request.phase());
        } else {
            artifactJobService.markRunning(taskId);
        }
        if (waitingPhase) {
            taskService.recordWaiting(taskId, request.phase(), request.message(), request.progressPercent(), request.metrics(), request.payload());
            return new WorkerAckResponse(taskId, "WAITING", "");
        }
        taskService.recordProgress(taskId, request.phase(), request.message(), request.progressPercent(), request.metrics(), request.payload());
        return new WorkerAckResponse(taskId, "RUNNING", "");
    }

    @Transactional
    public WorkerAckResponse complete(String taskId, WorkerCompleteRequest request) {
        return complete(taskId, request, "");
    }

    @Transactional
    public WorkerAckResponse complete(String taskId, WorkerCompleteRequest request, String idempotencyKey) {
        return completeInternal(taskId, request, idempotencyKey, "", false);
    }

    @Transactional
    public WorkerAckResponse completeFromDelivery(
            String taskId,
            WorkerCompleteRequest request,
            String idempotencyKey,
            String deliveryToken
    ) {
        return completeInternal(taskId, request, idempotencyKey, deliveryToken, true);
    }

    private WorkerAckResponse completeInternal(
            String taskId,
            WorkerCompleteRequest request,
            String idempotencyKey,
            String deliveryToken,
            boolean requireDelivery
    ) {
        if (!requireDelivery) {
            requireTerminalIdempotencyKey(idempotencyKey);
        }
        requireDeliveryOwnership(taskId, deliveryToken, requireDelivery);
        TaskService.TaskRef task = taskService.getTaskRef(taskId);
        if (isTerminalStatus(task.taskStatus())) {
            validateTerminalCallback(taskId, idempotencyKey, "COMPLETE", request);
            return terminalAck(taskId, task.taskStatus());
        }
        validateCallbackOwnership(task);
        if (requireDelivery) {
            requireTerminalIdempotencyKey(idempotencyKey);
        }
        lockTask(taskId);
        task = taskService.getTaskRef(taskId);
        if (isTerminalStatus(task.taskStatus())) {
            validateTerminalCallback(taskId, idempotencyKey, "COMPLETE", request);
            return terminalAck(taskId, task.taskStatus());
        }
        validateCallbackOwnership(task);
        WorkerAckResponse duplicate = claimCallback(taskId, idempotencyKey, "COMPLETE", request);
        if (duplicate != null) return duplicate;
        if ("PENDING".equals(task.taskStatus())) {
            taskService.startTask(taskId);
            task = taskService.getTaskRef(taskId);
        }
        CompletionOutcome outcome = artifactJobService.completeFromWorker(taskId, request);
        artifactExportService.materializeExports(outcome.resultRef());
        taskService.completeTask(taskId, outcome.phase(), outcome.message(), outcome.resultRef());
        acknowledgeDelivery(taskId, deliveryToken, requireDelivery);
        completeCallbackReceipt(taskId, idempotencyKey, outcome.resultRef());
        return new WorkerAckResponse(taskId, "COMPLETED", outcome.resultRef());
    }

    @Transactional
    public WorkerAckResponse fail(String taskId, WorkerFailRequest request) {
        return fail(taskId, request, "");
    }

    @Transactional
    public WorkerAckResponse fail(String taskId, WorkerFailRequest request, String idempotencyKey) {
        return failInternal(taskId, request, idempotencyKey, "", false);
    }

    @Transactional
    public WorkerAckResponse failFromDelivery(
            String taskId,
            WorkerFailRequest request,
            String idempotencyKey,
            String deliveryToken
    ) {
        return failInternal(taskId, request, idempotencyKey, deliveryToken, true);
    }

    private WorkerAckResponse failInternal(
            String taskId,
            WorkerFailRequest request,
            String idempotencyKey,
            String deliveryToken,
            boolean requireDelivery
    ) {
        if (!requireDelivery) {
            requireTerminalIdempotencyKey(idempotencyKey);
        }
        requireDeliveryOwnership(taskId, deliveryToken, requireDelivery);
        TaskService.TaskRef task = taskService.getTaskRef(taskId);
        if (isTerminalStatus(task.taskStatus())) {
            validateTerminalCallback(taskId, idempotencyKey, "FAIL", request);
            return terminalAck(taskId, task.taskStatus());
        }
        validateCallbackOwnership(task);
        if (requireDelivery) {
            requireTerminalIdempotencyKey(idempotencyKey);
        }
        lockTask(taskId);
        task = taskService.getTaskRef(taskId);
        if (isTerminalStatus(task.taskStatus())) {
            validateTerminalCallback(taskId, idempotencyKey, "FAIL", request);
            return terminalAck(taskId, task.taskStatus());
        }
        validateCallbackOwnership(task);
        WorkerAckResponse duplicate = claimCallback(taskId, idempotencyKey, "FAIL", request);
        if (duplicate != null) return duplicate;
        artifactJobService.markFailed(taskId, request);
        taskService.failTask(taskId, request.phase(), request.errorMessage(), request.errorCode(), request.retryable());
        acknowledgeDelivery(taskId, deliveryToken, requireDelivery);
        completeCallbackReceipt(taskId, idempotencyKey, "");
        return new WorkerAckResponse(taskId, "FAILED", "");
    }

    public record CompletionOutcome(String phase, String message, String resultRef) {
    }

    private boolean isWaitingPhase(String phase) {
        return phase != null && phase.startsWith("WAITING_FOR_");
    }

    private void validateWorkerType(TaskService.TaskRef task, String workerType) {
        if (!"artifact".equalsIgnoreCase(workerType == null ? "" : workerType.trim())) {
            throw new BusinessException(
                    "WORKER_CALLBACK_OWNER_MISMATCH",
                    "Worker type does not own this task",
                    HttpStatus.CONFLICT
            );
        }
    }

    private void validateCallbackOwnership(TaskService.TaskRef task) {
        if (!"ARTIFACT_JOB".equals(task.taskType()) || !"ARTIFACT_JOB".equals(task.targetType())) {
            throw new BusinessException(
                    "WORKER_CALLBACK_TASK_TYPE_INVALID",
                    "Task is not owned by an external worker",
                    HttpStatus.CONFLICT
            );
        }
        Integer matches = jdbcTemplate.queryForObject(
                "select count(*) from artifact_job where id = ? and task_id = ? and workspace_id = ?",
                Integer.class,
                task.targetId(),
                task.taskId(),
                task.workspaceId()
        );
        if (matches == null || matches != 1) {
            throw new BusinessException(
                    "WORKER_CALLBACK_OWNER_MISMATCH",
                    "Task target or workspace ownership does not match",
                    HttpStatus.CONFLICT
            );
        }
    }

    private boolean isTerminalStatus(String status) {
        return "COMPLETED".equalsIgnoreCase(status)
                || "FAILED".equalsIgnoreCase(status)
                || "CANCELLED".equalsIgnoreCase(status);
    }

    private WorkerAckResponse terminalAck(String taskId, String status) {
        String resultRef = taskService.getTask(taskId).resultRef();
        return new WorkerAckResponse(taskId, status.toUpperCase(), resultRef == null ? "" : resultRef);
    }

    private WorkerAckResponse claimCallback(
            String taskId,
            String idempotencyKey,
            String callbackType,
            Object request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        String normalizedKey = idempotencyKey.trim();
        String payloadDigest = callbackDigest(request);
        CallbackReceipt existing = findCallbackReceipt(taskId, normalizedKey);
        if (existing != null) return replayOrConflict(taskId, callbackType, payloadDigest, existing);
        try {
            jdbcTemplate.update(
                    """
                    insert into worker_callback_receipt(
                        id, task_id, idempotency_key, callback_type, payload_digest
                    ) values (?, ?, ?, ?, ?)
                    """,
                    Ids.newId(), taskId, normalizedKey, callbackType, payloadDigest
            );
            return null;
        } catch (DataIntegrityViolationException duplicate) {
            CallbackReceipt raced = findCallbackReceipt(taskId, normalizedKey);
            if (raced == null) throw duplicate;
            return replayOrConflict(taskId, callbackType, payloadDigest, raced);
        }
    }

    private void validateTerminalCallback(String taskId, String idempotencyKey, String callbackType, Object request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return;
        CallbackReceipt existing = findCallbackReceipt(taskId, idempotencyKey.trim());
        if (existing != null) replayOrConflict(taskId, callbackType, callbackDigest(request), existing);
    }

    private CallbackReceipt findCallbackReceipt(String taskId, String idempotencyKey) {
        List<CallbackReceipt> receipts = jdbcTemplate.query("""
                select callback_type, payload_digest, result_ref
                from worker_callback_receipt
                where task_id = ? and idempotency_key = ?
                """, (rs, rowNum) -> new CallbackReceipt(
                rs.getString("callback_type"), rs.getString("payload_digest"), rs.getString("result_ref")),
                taskId, idempotencyKey);
        return receipts.isEmpty() ? null : receipts.get(0);
    }

    private WorkerAckResponse replayOrConflict(
            String taskId,
            String callbackType,
            String payloadDigest,
            CallbackReceipt receipt
    ) {
        if (!callbackType.equals(receipt.callbackType()) || receipt.payloadDigest() == null
                || !payloadDigest.equals(receipt.payloadDigest())) {
            throw new BusinessException(
                    "WORKER_CALLBACK_IDEMPOTENCY_CONFLICT",
                    "Callback idempotency key is bound to different content",
                    HttpStatus.CONFLICT);
        }
        String resultRef = receipt.resultRef();
        if (resultRef == null) resultRef = taskService.getTask(taskId).resultRef();
        return new WorkerAckResponse(taskId, "DUPLICATE_" + callbackType,
                resultRef == null ? "" : resultRef);
    }

    private void completeCallbackReceipt(String taskId, String idempotencyKey, String resultRef) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return;
        jdbcTemplate.update("""
                update worker_callback_receipt
                set result_ref = ?, completed_at = current_timestamp
                where task_id = ? and idempotency_key = ?
                """, resultRef == null || resultRef.isBlank() ? null : resultRef,
                taskId, idempotencyKey.trim());
    }

    private String callbackDigest(Object request) {
        try {
            Object value = objectMapper.convertValue(request, Object.class);
            String canonical = objectMapper.writeValueAsString(canonicalValue(value));
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Worker callback payload cannot be canonicalized", exception);
        }
    }

    private Object canonicalValue(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> sorted = new TreeMap<>();
            raw.forEach((key, child) -> sorted.put(String.valueOf(key), canonicalValue(child)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(this::canonicalValue).toList();
        return value;
    }

    private record CallbackReceipt(String callbackType, String payloadDigest, String resultRef) { }

    private void requireTerminalIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(
                    "WORKER_CALLBACK_IDEMPOTENCY_REQUIRED",
                    "Complete and fail callbacks require an idempotency key",
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    private void lockTask(String taskId) {
        jdbcTemplate.query(
                "select id from task where id = ? for update",
                rs -> rs.next() ? rs.getString(1) : null,
                taskId
        );
    }

    private void renewDelivery(String taskId, String deliveryToken, boolean required) {
        if (!required) {
            return;
        }
        if (!outboxDispatcher.renewTaskMessage(
                ARTIFACT_TOPIC,
                taskId,
                deliveryToken,
                OutboxDispatchPolicy.ARTIFACT_LEASE_DURATION
        )) {
            throw invalidDeliveryToken();
        }
    }

    private void requireDeliveryOwnership(String taskId, String deliveryToken, boolean required) {
        if (required && !outboxDispatcher.ownsTaskMessage(ARTIFACT_TOPIC, taskId, deliveryToken)) {
            throw invalidDeliveryToken();
        }
    }

    private void acknowledgeDelivery(String taskId, String deliveryToken, boolean required) {
        if (required && !outboxDispatcher.acknowledgeTaskMessage(
                ARTIFACT_TOPIC, taskId, deliveryToken
        )) {
            throw invalidDeliveryToken();
        }
    }

    private BusinessException invalidDeliveryToken() {
        return new BusinessException(
                "WORKER_CALLBACK_DELIVERY_STALE",
                "Worker callback does not own the active outbox delivery",
                HttpStatus.CONFLICT
        );
    }

}
