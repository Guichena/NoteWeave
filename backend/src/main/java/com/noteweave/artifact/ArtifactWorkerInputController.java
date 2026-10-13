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
    private final ArtifactVideoMaterialService videoMaterials;
    private final ArtifactWorkerDeliveryGuard deliveryGuard;

    public ArtifactWorkerInputController(
            ArtifactJobService artifactJobService,
            ArtifactVideoMaterialService videoMaterials,
            DurableOutboxDispatcher outboxDispatcher,
            @Value("${noteweave.environment:development}") String environment,
            @Value("${noteweave.worker.artifact-input-unfenced-enabled:false}")
            boolean unfencedInputEnabled
    ) {
        this.artifactJobService = artifactJobService;
        this.videoMaterials = videoMaterials;
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

    @PostMapping("/{taskId}/video-material")
    ApiResponse<ArtifactVideoMaterialService.Receipt> submitVideoMaterial(
            @PathVariable String taskId,
            @RequestBody ArtifactVideoMaterialService.Submission submission,
            @RequestHeader(value = DurableOutboxDispatcher.DELIVERY_TOKEN_HEADER,
                    defaultValue = "") String deliveryToken
    ) {
        requireDelivery(taskId, deliveryToken);
        return ApiResponse.success(videoMaterials.submit(taskId, submission));
    }

    @GetMapping("/{taskId}/video-material")
    ApiResponse<Map<String, Object>> getVideoMaterial(
            @PathVariable String taskId,
            @RequestHeader(value = DurableOutboxDispatcher.DELIVERY_TOKEN_HEADER,
                    defaultValue = "") String deliveryToken
    ) {
        requireDelivery(taskId, deliveryToken);
        return ApiResponse.success(videoMaterials.read(taskId));
    }

    @PostMapping("/{taskId}/video-material/knowledge-plan")
    ApiResponse<ArtifactVideoMaterialService.KnowledgeReceipt> submitVideoKnowledgePlan(
            @PathVariable String taskId,
            @RequestBody ArtifactVideoMaterialService.KnowledgeSubmission submission,
            @RequestHeader(value = DurableOutboxDispatcher.DELIVERY_TOKEN_HEADER,
                    defaultValue = "") String deliveryToken
    ) {
        requireDelivery(taskId, deliveryToken);
        return ApiResponse.success(videoMaterials.submitKnowledgePlan(taskId, submission));
    }

    @GetMapping("/{taskId}/video-material/knowledge-plan/{bundleId}")
    ApiResponse<Map<String, Object>> getVideoKnowledgePlan(
            @PathVariable String taskId, @PathVariable String bundleId,
            @RequestHeader(value = DurableOutboxDispatcher.DELIVERY_TOKEN_HEADER,
                    defaultValue = "") String deliveryToken
    ) {
        requireDelivery(taskId, deliveryToken);
        return ApiResponse.success(videoMaterials.readKnowledgePlan(taskId, bundleId));
    }

    @GetMapping("/{taskId}/video-material/references/{bundleId}/knowledge-plan")
    ApiResponse<Map<String, Object>> getReferencedVideoKnowledgePlan(
            @PathVariable String taskId, @PathVariable String bundleId,
            @RequestHeader(value = DurableOutboxDispatcher.DELIVERY_TOKEN_HEADER,
                    defaultValue = "") String deliveryToken
    ) {
        requireDelivery(taskId, deliveryToken);
        return ApiResponse.success(videoMaterials.readReferencedKnowledgePlan(taskId, bundleId));
    }

    @GetMapping("/{taskId}/video-material/files/{fileId}")
    ResponseEntity<byte[]> getVideoMaterialFile(
            @PathVariable String taskId, @PathVariable String fileId,
            @RequestHeader(value = DurableOutboxDispatcher.DELIVERY_TOKEN_HEADER,
                    defaultValue = "") String deliveryToken
    ) {
        requireDelivery(taskId, deliveryToken);
        ArtifactVideoMaterialService.MaterialBytes file = videoMaterials.readFile(taskId, fileId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType()))
                .body(file.bytes());
    }

    @GetMapping("/{taskId}/video-material/references/{bundleId}")
    ApiResponse<Map<String, Object>> getReferencedVideoMaterial(
            @PathVariable String taskId, @PathVariable String bundleId,
            @RequestHeader(value = DurableOutboxDispatcher.DELIVERY_TOKEN_HEADER,
                    defaultValue = "") String deliveryToken
    ) {
        requireDelivery(taskId, deliveryToken);
        return ApiResponse.success(videoMaterials.readReferenced(taskId, bundleId));
    }

    @GetMapping("/{taskId}/video-material/references/{bundleId}/files/{fileId}")
    ResponseEntity<byte[]> getReferencedVideoMaterialFile(
            @PathVariable String taskId, @PathVariable String bundleId, @PathVariable String fileId,
            @RequestHeader(value = DurableOutboxDispatcher.DELIVERY_TOKEN_HEADER,
                    defaultValue = "") String deliveryToken
    ) {
        requireDelivery(taskId, deliveryToken);
        ArtifactVideoMaterialService.MaterialBytes file =
                videoMaterials.readReferencedFile(taskId, bundleId, fileId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType()))
                .body(file.bytes());
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
