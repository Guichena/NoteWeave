package com.noteweave.worker;

import com.noteweave.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/internal/worker/artifact-outbox")
public class ArtifactOutboxDispatcherController {

    private final ArtifactOutboxDispatcherService artifactOutboxDispatcherService;

    public ArtifactOutboxDispatcherController(ArtifactOutboxDispatcherService artifactOutboxDispatcherService) {
        this.artifactOutboxDispatcherService = artifactOutboxDispatcherService;
    }

    @PostMapping("/dispatch")
    ApiResponse<ArtifactOutboxDispatchResponse> dispatch(
            @RequestParam(defaultValue = "5") int limit
    ) {
        return ApiResponse.success(artifactOutboxDispatcherService.dispatchReadyArtifactJobs(limit));
    }

    @GetMapping("/metrics")
    ApiResponse<ArtifactOutboxMetricsResponse> metrics() {
        return ApiResponse.success(artifactOutboxDispatcherService.metrics());
    }

    @GetMapping("/dead-letters")
    ApiResponse<List<ArtifactOutboxDeadLetterResponse>> deadLetters(
            @RequestParam(defaultValue = "50") int limit
    ) {
        return ApiResponse.success(artifactOutboxDispatcherService.listDeadLetters(limit));
    }

    @PostMapping("/dead-letters/{outboxId}/redrive")
    ApiResponse<ArtifactOutboxMetricsResponse> redrive(@PathVariable String outboxId) {
        artifactOutboxDispatcherService.redrive(outboxId);
        return ApiResponse.success(artifactOutboxDispatcherService.metrics());
    }
}
