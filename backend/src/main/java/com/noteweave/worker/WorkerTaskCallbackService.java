package com.noteweave.worker;

import com.noteweave.artifact.ArtifactJobService;
import com.noteweave.artifact.ArtifactExportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.answer.AnswerLiveEvent;
import com.noteweave.answer.ConversationEventMux;
import com.noteweave.common.Json;
import com.noteweave.research.ResearchRunService;
import com.noteweave.task.TaskService;
import java.util.Map;
import com.noteweave.common.Ids;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkerTaskCallbackService {

    private final TaskService taskService;
    private final ArtifactJobService artifactJobService;
    private final ArtifactExportService artifactExportService;
    private final ResearchRunService researchRunService;
    private final JdbcTemplate jdbcTemplate;
    private final ConversationEventMux conversationEventMux;
    private final ObjectMapper objectMapper;

    @Autowired
    public WorkerTaskCallbackService(
            TaskService taskService,
            ArtifactJobService artifactJobService,
            ArtifactExportService artifactExportService,
            ResearchRunService researchRunService,
            JdbcTemplate jdbcTemplate,
            ConversationEventMux conversationEventMux,
            ObjectMapper objectMapper
    ) {
        this.taskService = taskService;
        this.artifactJobService = artifactJobService;
        this.artifactExportService = artifactExportService;
        this.researchRunService = researchRunService;
        this.jdbcTemplate = jdbcTemplate;
        this.conversationEventMux = conversationEventMux;
        this.objectMapper = objectMapper;
    }

    WorkerTaskCallbackService(
            TaskService taskService,
            ArtifactJobService artifactJobService,
            ArtifactExportService artifactExportService,
            ResearchRunService researchRunService,
            JdbcTemplate jdbcTemplate
    ) {
        this(taskService, artifactJobService, artifactExportService, researchRunService,
                jdbcTemplate, null, new ObjectMapper());
    }

    @Transactional
    public WorkerAckResponse heartbeat(String taskId, WorkerHeartbeatRequest request) {
        return heartbeat(taskId, request, "");
    }

    @Transactional
    public WorkerAckResponse heartbeat(String taskId, WorkerHeartbeatRequest request, String idempotencyKey) {
        TaskService.TaskRef task = taskService.getTaskRef(taskId);
        if (isTerminalStatus(task.taskStatus())) {
            return terminalAck(taskId, task.taskStatus());
        }
        if ("PENDING".equals(task.taskStatus())) {
            taskService.startTask(taskId);
            task = taskService.getTaskRef(taskId);
        }
        WorkerAckResponse duplicate = registerCallback(taskId, idempotencyKey, "HEARTBEAT");
        if (duplicate != null) return duplicate;
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
        TaskService.TaskRef task = taskService.getTaskRef(taskId);
        if (isTerminalStatus(task.taskStatus())) {
            return terminalAck(taskId, task.taskStatus());
        }
        if ("PENDING".equals(task.taskStatus())) {
            taskService.startTask(taskId);
            task = taskService.getTaskRef(taskId);
        }
        WorkerAckResponse duplicate = registerCallback(taskId, idempotencyKey, "PROGRESS");
        if (duplicate != null) return duplicate;
        boolean waitingPhase = isWaitingPhase(request.phase());
        switch (task.taskType()) {
            case "ARTIFACT_JOB" -> {
                if (waitingPhase) {
                    artifactJobService.markWaiting(taskId, request.phase());
                } else {
                    artifactJobService.markRunning(taskId);
                }
            }
            case "RESEARCH_RUN" -> {
                if ("RESEARCH_CHECKPOINT".equalsIgnoreCase(request.phase())) {
                    researchRunService.persistRuntimeCheckpoint(taskId, request.payload());
                }
                if (waitingPhase) {
                    researchRunService.markWaiting(taskId, request.phase(), request.message(), request.metrics());
                } else {
                    researchRunService.markRunning(taskId, request.phase(), request.message(), request.metrics());
                }
            }
            default -> {
            }
        }
        if (waitingPhase) {
            taskService.recordWaiting(taskId, request.phase(), request.message(), request.progressPercent(), request.metrics(), request.payload());
            projectResearchProgress(task, request);
            return new WorkerAckResponse(taskId, "WAITING", "");
        }
        taskService.recordProgress(taskId, request.phase(), request.message(), request.progressPercent(), request.metrics(), request.payload());
        projectResearchProgress(task, request);
        return new WorkerAckResponse(taskId, "RUNNING", "");
    }

    @Transactional
    public WorkerAckResponse complete(String taskId, WorkerCompleteRequest request) {
        return complete(taskId, request, "");
    }

    @Transactional
    public WorkerAckResponse complete(String taskId, WorkerCompleteRequest request, String idempotencyKey) {
        return complete(taskId, request, idempotencyKey, idempotencyKey, null, null);
    }

    @Transactional
    public WorkerAckResponse complete(
            String taskId,
            WorkerCompleteRequest request,
            String idempotencyKey,
            String callbackEventId,
            Integer attemptNo,
            Long fencingToken
    ) {
        TaskService.TaskRef task = taskService.getTaskRef(taskId);
        validateResearchCallback(task, callbackEventId, attemptNo, fencingToken);
        if (isTerminalStatus(task.taskStatus())) {
            return terminalAck(taskId, task.taskStatus());
        }
        if ("PENDING".equals(task.taskStatus())) {
            taskService.startTask(taskId);
            task = taskService.getTaskRef(taskId);
        }
        String effectiveEventId = callbackEventId == null || callbackEventId.isBlank()
                ? idempotencyKey : callbackEventId;
        WorkerAckResponse duplicate = registerCallback(taskId, effectiveEventId, "COMPLETE");
        if (duplicate != null) return duplicate;
        CompletionOutcome outcome = switch (task.taskType()) {
            case "ARTIFACT_JOB" -> artifactJobService.completeFromWorker(taskId, request);
            case "RESEARCH_RUN" -> researchRunService.completeFromWorker(taskId, request);
            default -> new CompletionOutcome("COMPLETED", request.resultTitle(), "");
        };
        if ("ARTIFACT_JOB".equals(task.taskType())) {
            artifactExportService.materializeExports(outcome.resultRef());
        }
        taskService.completeTask(taskId, outcome.phase(), outcome.message(), outcome.resultRef());
        return new WorkerAckResponse(taskId, "COMPLETED", outcome.resultRef());
    }

    private void validateResearchCallback(
            TaskService.TaskRef task,
            String callbackEventId,
            Integer attemptNo,
            Long fencingToken
    ) {
        if (!"RESEARCH_RUN".equals(task.taskType())) {
            return;
        }
        java.util.List<ResearchCallbackFence> fences = jdbcTemplate.query("""
                select conversation_id, attempt_no, fencing_token
                from research_run
                where id = ?
                """, (rs, rowNum) -> new ResearchCallbackFence(
                rs.getString("conversation_id"),
                rs.getInt("attempt_no"),
                rs.getLong("fencing_token")
        ), task.targetId());
        if (fences.isEmpty() || fences.get(0).conversationId() == null) {
            return;
        }
        ResearchCallbackFence current = fences.get(0);
        boolean missingEvent = callbackEventId == null || callbackEventId.isBlank();
        boolean stale = attemptNo == null || fencingToken == null
                || attemptNo != current.attemptNo()
                || fencingToken != current.fencingToken();
        if (missingEvent || stale) {
            throw new com.noteweave.common.BusinessException(
                    "RESEARCH_CALLBACK_FENCED",
                    "Research callback attempt or fencing token is not current",
                    org.springframework.http.HttpStatus.CONFLICT
            );
        }
    }

    @Transactional
    public WorkerAckResponse fail(String taskId, WorkerFailRequest request) {
        return fail(taskId, request, "");
    }

    @Transactional
    public WorkerAckResponse fail(String taskId, WorkerFailRequest request, String idempotencyKey) {
        TaskService.TaskRef task = taskService.getTaskRef(taskId);
        if (isTerminalStatus(task.taskStatus())) {
            return terminalAck(taskId, task.taskStatus());
        }
        WorkerAckResponse duplicate = registerCallback(taskId, idempotencyKey, "FAIL");
        if (duplicate != null) return duplicate;
        switch (task.taskType()) {
            case "ARTIFACT_JOB" -> artifactJobService.markFailed(taskId, request);
            case "RESEARCH_RUN" -> researchRunService.markFailed(taskId, request);
            default -> {
            }
        }
        taskService.failTask(taskId, request.phase(), request.errorMessage(), request.errorCode(), request.retryable());
        return new WorkerAckResponse(taskId, "FAILED", "");
    }

    public record CompletionOutcome(String phase, String message, String resultRef) {
    }

    private boolean isWaitingPhase(String phase) {
        return phase != null && phase.startsWith("WAITING_FOR_");
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

    private WorkerAckResponse registerCallback(String taskId, String idempotencyKey, String callbackType) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        try {
            jdbcTemplate.update(
                    "insert into worker_callback_receipt(id, task_id, idempotency_key, callback_type) values (?, ?, ?, ?)",
                    Ids.newId(), taskId, idempotencyKey.trim(), callbackType
            );
            return null;
        } catch (DataIntegrityViolationException duplicate) {
            TaskService.TaskRef task = taskService.getTaskRef(taskId);
            return new WorkerAckResponse(taskId, "DUPLICATE_" + callbackType, taskService.getTask(taskId).resultRef());
        }
    }

    private void projectResearchProgress(TaskService.TaskRef task, WorkerProgressRequest request) {
        if (conversationEventMux == null || !"RESEARCH_RUN".equals(task.taskType())) {
            return;
        }
        String conversationId = jdbcTemplate.query("""
                select conversation_id from research_run where id = ?
                """, rs -> rs.next() ? rs.getString("conversation_id") : null, task.targetId());
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        Long runSequence = jdbcTemplate.queryForObject(
                "select count(*) from task_event where task_id = ?", Long.class, task.taskId());
        conversationEventMux.publish(conversationId, task.targetId(), new AnswerLiveEvent(
                runSequence == null ? 0 : runSequence,
                "research.progress",
                Json.write(objectMapper, Map.of(
                        "research_run_id", task.targetId(),
                        "task_id", task.taskId(),
                        "phase", request.phase(),
                        "progress_percent", request.progressPercent() == null ? 0 : request.progressPercent(),
                        "message", request.message(),
                        "metrics", request.metrics() == null ? Map.of() : request.metrics(),
                        "payload", request.payload() == null ? Map.of() : request.payload()
                )),
                java.time.Instant.now()
        ));
    }

    private record ResearchCallbackFence(String conversationId, int attemptNo, long fencingToken) {
    }
}
