package com.noteweave.artifact;

import com.noteweave.infra.outbox.DurableOutboxDispatcher;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ArtifactTaskFrameController {
    private final ArtifactTaskFrameService frames;
    private final ArtifactWorkerDeliveryGuard delivery;

    public ArtifactTaskFrameController(ArtifactTaskFrameService frames, ArtifactWorkerDeliveryGuard delivery) {
        this.frames = frames;
        this.delivery = delivery;
    }

    @GetMapping("/internal/worker/artifact-tasks/{taskId}/frames/{frameId}")
    ResponseEntity<byte[]> readFrame(@PathVariable String taskId, @PathVariable String frameId,
            @RequestHeader(value = DurableOutboxDispatcher.DELIVERY_TOKEN_HEADER, defaultValue = "") String token) {
        delivery.requireDelivery(taskId, token);
        var image = frames.readFrame(taskId, frameId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.mediaType()))
                .header("Cache-Control", "no-store").body(image.content());
    }
}
