package com.noteweave.artifact;

import com.noteweave.common.ApiResponse;
import com.noteweave.infra.outbox.DurableOutboxDispatcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/internal/worker/artifact-tasks")
public class ArtifactWorkerInputController {

    private final ArtifactJobService artifactJobService;
    private final ArtifactWorkerDeliveryGuard deliveryGuard;

    public ArtifactWorkerInputController(
            ArtifactJobService artifactJobService,
            DurableOutboxDispatcher outboxDispatcher,
            @Value("${noteweave.environment:development}") String environment,
            @Value("${noteweave.worker.artifact-input-unfenced-enabled:false}")
            boolean unfencedInputEnabled
    ) {
        this.artifactJobService = artifactJobService;
        this.deliveryGuard = new ArtifactWorkerDeliveryGuard(outboxDispatcher, environment, unfencedInputEnabled);
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
        deliveryGuard.requireDelivery(taskId, deliveryToken);
    }
}
