package com.noteweave.worker;

import com.noteweave.artifact.ArtifactJobService;
import com.noteweave.research.ResearchRunService;
import com.noteweave.task.TaskService;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkerTaskCallbackService {

    private final TaskService taskService;
    private final ArtifactJobService artifactJobService;
    private final ResearchRunService researchRunService;

    public WorkerTaskCallbackService(
            TaskService taskService,
            ArtifactJobService artifactJobService,
            ResearchRunService researchRunService
    ) {
        this.taskService = taskService;
        this.artifactJobService = artifactJobService;
        this.researchRunService = researchRunService;
    }

    @Transactional
    public WorkerAckResponse heartbeat(String taskId, WorkerHeartbeatRequest request) {
        taskService.recordHeartbeat(taskId, request.phase(), request.workerType() + " heartbeat", Map.of(
                "worker_type", request.workerType(),
                "worker_instance_id", request.workerInstanceId(),
                "heartbeat_at", request.heartbeatAt()
        ));
        return new WorkerAckResponse(taskId, "HEARTBEAT_RECORDED", "");
    }

    @Transactional
    public WorkerAckResponse progress(String taskId, WorkerProgressRequest request) {
        TaskService.TaskRef task = taskService.getTaskRef(taskId);
        switch (task.taskType()) {
            case "ARTIFACT_JOB" -> artifactJobService.markRunning(taskId);
            case "RESEARCH_RUN" -> researchRunService.markRunning(taskId, request.phase(), request.message(), request.metrics());
            default -> {
            }
        }
        taskService.recordProgress(taskId, request.phase(), request.message(), request.progressPercent(), request.metrics());
        return new WorkerAckResponse(taskId, "RUNNING", "");
    }

    @Transactional
    public WorkerAckResponse complete(String taskId, WorkerCompleteRequest request) {
        TaskService.TaskRef task = taskService.getTaskRef(taskId);
        CompletionOutcome outcome = switch (task.taskType()) {
            case "ARTIFACT_JOB" -> artifactJobService.completeFromWorker(taskId, request);
            case "RESEARCH_RUN" -> researchRunService.completeFromWorker(taskId, request);
            default -> new CompletionOutcome("COMPLETED", request.resultTitle(), "");
        };
        taskService.completeTask(taskId, outcome.phase(), outcome.message(), outcome.resultRef());
        return new WorkerAckResponse(taskId, "COMPLETED", outcome.resultRef());
    }

    @Transactional
    public WorkerAckResponse fail(String taskId, WorkerFailRequest request) {
        TaskService.TaskRef task = taskService.getTaskRef(taskId);
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
}
