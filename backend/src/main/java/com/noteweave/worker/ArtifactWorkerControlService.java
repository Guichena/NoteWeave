package com.noteweave.worker;

import java.util.List;
import java.util.Map;
import java.util.Set;
import com.noteweave.artifact.VideoMaterialTaskService;
import com.noteweave.common.SensitiveErrorMessageSanitizer;
import com.noteweave.infra.outbox.DurableOutboxDispatcher;
import com.noteweave.task.TaskService;
import org.springframework.stereotype.Service;

@Service
public class ArtifactWorkerControlService {

    private final ArtifactWorkerControlClient artifactWorkerControlClient;
    private final WorkerTaskCallbackService workerTaskCallbackService;
    private final DurableOutboxDispatcher outboxDispatcher;
    private final TaskService taskService;
    private final VideoMaterialTaskService videoMaterialTaskService;

    public ArtifactWorkerControlService(
            ArtifactWorkerControlClient artifactWorkerControlClient,
            WorkerTaskCallbackService workerTaskCallbackService,
            DurableOutboxDispatcher outboxDispatcher,
            TaskService taskService,
            VideoMaterialTaskService videoMaterialTaskService
    ) {
        this.artifactWorkerControlClient = artifactWorkerControlClient;
        this.workerTaskCallbackService = workerTaskCallbackService;
        this.outboxDispatcher = outboxDispatcher;
        this.taskService = taskService;
        this.videoMaterialTaskService = videoMaterialTaskService;
    }

    public ArtifactWorkerExecutionResponse resumeTask(String taskId, ArtifactWorkerResumeRequest request) {
        ArtifactWorkerResumeRequest normalizedRequest = request == null
                ? new ArtifactWorkerResumeRequest("", activeDeliveryToken(taskId))
                : new ArtifactWorkerResumeRequest(
                        blankIfNull(request.requestId()), activeDeliveryToken(taskId)
                );
        return artifactWorkerControlClient.resumeTask(taskId, normalizedRequest);
    }

    public ArtifactAcquisitionAckResponse acknowledgeAcquisition(ArtifactAcquisitionAckRequest request) {
        ArtifactAcquisitionAckRequest normalizedRequest = request == null
                ? new ArtifactAcquisitionAckRequest("", "", "", "", "", Map.of())
                : new ArtifactAcquisitionAckRequest(
                        blankIfNull(request.callbackToken()),
                        blankIfNull(request.finalStatus()),
                        blankIfNull(request.resultLocator()),
                        blankIfNull(request.errorCode()),
                        blankIfNull(request.errorMessage()),
                        request.providerPayload() == null ? Map.of() : request.providerPayload()
                );
        ArtifactAcquisitionAckResponse response = artifactWorkerControlClient.acknowledgeAcquisition(normalizedRequest);
        propagateProviderFailureIfNeeded(response, normalizedRequest);
        if (response != null && response.receipt() != null && response.operation() != null
                && "ACKNOWLEDGED".equalsIgnoreCase(blankIfNull(response.receipt().callbackStatus()))
                && (response.resumedTasks() == null || response.resumedTasks().isEmpty())
                && !blankIfNull(response.operation().taskId()).isBlank()) {
            String taskId = response.operation().taskId();
            if (Set.of("COMPLETED", "FAILED", "CANCELLED")
                    .contains(taskService.getTaskRef(taskId).taskStatus().toUpperCase())) {
                return response;
            }
            String deliveryToken = activeDeliveryToken(taskId);
            if (deliveryToken == null || deliveryToken.isBlank()) {
                throw new IllegalStateException("provider callback cannot resume without an active artifact delivery");
            }
            ArtifactWorkerExecutionResponse resumed = artifactWorkerControlClient.resumeTask(taskId,
                    new ArtifactWorkerResumeRequest(response.operation().requestId(), deliveryToken));
            return new ArtifactAcquisitionAckResponse(response.operation(), response.receipt(), List.of(resumed));
        }
        return response;
    }

    private String blankIfNull(String value) {
        return value == null ? "" : value;
    }

    private void propagateProviderFailureIfNeeded(
            ArtifactAcquisitionAckResponse response,
            ArtifactAcquisitionAckRequest request
    ) {
        if (response == null || response.receipt() == null) {
            return;
        }
        String callbackStatus = blankIfNull(response.receipt().callbackStatus()).toUpperCase();
        if (!"FAILED".equals(callbackStatus)) {
            return;
        }
        String taskId = response.operation() == null ? "" : blankIfNull(response.operation().taskId());
        if (taskId.isBlank()) {
            return;
        }
        String errorCode = blankIfNull(response.receipt().errorCode());
        if (errorCode.isBlank()) {
            errorCode = blankIfNull(request.errorCode());
        }
        if (errorCode.isBlank()) {
            errorCode = "PROVIDER_CALLBACK_FAILED";
        }
        String errorMessage = blankIfNull(response.receipt().errorMessage());
        if (errorMessage.isBlank()) {
            errorMessage = blankIfNull(request.errorMessage());
        }
        if (errorMessage.isBlank()) {
            errorMessage = "provider callback reported acquisition failure";
        }
        errorMessage = SensitiveErrorMessageSanitizer.sanitize(errorMessage);
        String deliveryToken = activeDeliveryToken(taskId);
        if ("VIDEO_MATERIAL".equals(taskService.getTaskRef(taskId).taskType())) {
            String materialErrorCode = errorCode.matches("[A-Z][A-Z0-9_]{0,79}")
                    ? errorCode : "PROVIDER_CALLBACK_FAILED";
            videoMaterialTaskService.fail(taskId, deliveryToken, materialErrorCode);
            return;
        }
        workerTaskCallbackService.failFromDelivery(
                taskId,
                new WorkerFailRequest(
                        "WAITING_FOR_PROVIDER",
                        errorCode,
                        errorMessage,
                        true
                ),
                "artifact-provider-fail:" + taskId + ":" + blankIfNull(request.callbackToken()),
                deliveryToken
        );
    }

    private String activeDeliveryToken(String taskId) {
        return outboxDispatcher.activeTaskDeliveryToken("noteweave.artifact.job", taskId);
    }
}
