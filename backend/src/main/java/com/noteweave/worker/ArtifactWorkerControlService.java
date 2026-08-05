package com.noteweave.worker;

import java.util.Map;
import com.noteweave.common.SensitiveErrorMessageSanitizer;
import com.noteweave.infra.outbox.DurableOutboxDispatcher;
import org.springframework.stereotype.Service;

@Service
public class ArtifactWorkerControlService {

    private final ArtifactWorkerControlClient artifactWorkerControlClient;
    private final WorkerTaskCallbackService workerTaskCallbackService;
    private final DurableOutboxDispatcher outboxDispatcher;

    public ArtifactWorkerControlService(
            ArtifactWorkerControlClient artifactWorkerControlClient,
            WorkerTaskCallbackService workerTaskCallbackService,
            DurableOutboxDispatcher outboxDispatcher
    ) {
        this.artifactWorkerControlClient = artifactWorkerControlClient;
        this.workerTaskCallbackService = workerTaskCallbackService;
        this.outboxDispatcher = outboxDispatcher;
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
        workerTaskCallbackService.failFromDelivery(
                taskId,
                new WorkerFailRequest(
                        "WAITING_FOR_PROVIDER",
                        errorCode,
                        errorMessage,
                        true
                ),
                "artifact-provider-fail:" + taskId + ":" + blankIfNull(request.callbackToken()),
                activeDeliveryToken(taskId)
        );
    }

    private String activeDeliveryToken(String taskId) {
        return outboxDispatcher.activeTaskDeliveryToken("noteweave.artifact.job", taskId);
    }
}
