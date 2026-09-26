package com.noteweave.artifact;

import com.noteweave.common.ApiResponse;
import com.noteweave.common.BusinessException;
import com.noteweave.config.ProductionConfigurationGuard;
import com.noteweave.infra.outbox.DurableOutboxDispatcher;
import com.noteweave.infra.outbox.OutboxDispatchPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/worker/artifact-tasks")
public class ArtifactWorkerInputController {

    private final ArtifactJobService artifactJobService;
    private final DurableOutboxDispatcher outboxDispatcher;
    private final String environment;
    private final boolean unfencedInputEnabled;

    public ArtifactWorkerInputController(
            ArtifactJobService artifactJobService,
            DurableOutboxDispatcher outboxDispatcher,
            @Value("${noteweave.environment:development}") String environment,
            @Value("${noteweave.worker.artifact-input-unfenced-enabled:false}")
            boolean unfencedInputEnabled
    ) {
        this.artifactJobService = artifactJobService;
        this.outboxDispatcher = outboxDispatcher;
        this.environment = environment == null ? "development" : environment;
        this.unfencedInputEnabled = unfencedInputEnabled;
    }

    @GetMapping("/{taskId}/input")
    ApiResponse<ArtifactWorkerInputResponse> getTaskInput(
            @PathVariable String taskId,
            @RequestHeader(
                    value = DurableOutboxDispatcher.DELIVERY_TOKEN_HEADER,
                    defaultValue = ""
            ) String deliveryToken
    ) {
        requireDelivery(taskId, deliveryToken);
        return ApiResponse.success(artifactJobService.getWorkerInput(taskId));
    }

    @GetMapping("/{taskId}/sources/{sourceId}/windows")
    ApiResponse<ArtifactSourceWindowPageResponse> getSourceWindows(
            @PathVariable String taskId,
            @PathVariable String sourceId,
            @RequestParam String sourceSnapshotId,
            @RequestParam(defaultValue = "") String cursor,
            @RequestParam(defaultValue = "16") int maxWindows,
            @RequestParam(defaultValue = "65536") int maxBytes,
            @RequestHeader(
                    value = DurableOutboxDispatcher.DELIVERY_TOKEN_HEADER,
                    defaultValue = ""
            ) String deliveryToken
    ) {
        requireDelivery(taskId, deliveryToken);
        return ApiResponse.success(artifactJobService.readSourceWindows(
                taskId, sourceId, sourceSnapshotId, cursor, maxWindows, maxBytes));
    }

    private void requireDelivery(String taskId, String deliveryToken) {
        if (deliveryToken.isBlank() && !allowsUnfencedInput()) {
            throw new BusinessException(
                    "WORKER_INPUT_DELIVERY_TOKEN_REQUIRED",
                    "Production Artifact input requests require an active outbox delivery token",
                    HttpStatus.CONFLICT
            );
        }
        if (!deliveryToken.isBlank() && !outboxDispatcher.renewTaskMessage(
                "noteweave.artifact.job",
                taskId,
                deliveryToken,
                OutboxDispatchPolicy.ARTIFACT_LEASE_DURATION
        )) {
            throw new BusinessException(
                    "WORKER_INPUT_DELIVERY_STALE",
                    "Artifact input request does not own the active outbox delivery",
                    HttpStatus.CONFLICT
            );
        }
    }

    private boolean allowsUnfencedInput() {
        return unfencedInputEnabled && !ProductionConfigurationGuard.isProduction(environment);
    }
}
