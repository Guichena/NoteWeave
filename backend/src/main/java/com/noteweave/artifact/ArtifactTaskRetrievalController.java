package com.noteweave.artifact;

import com.noteweave.common.ApiResponse;
import com.noteweave.infra.outbox.DurableOutboxDispatcher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ArtifactTaskRetrievalController {
    private final ArtifactTaskRetrievalService retrieval;
    private final ArtifactWorkerDeliveryGuard delivery;

    public ArtifactTaskRetrievalController(ArtifactTaskRetrievalService retrieval, ArtifactWorkerDeliveryGuard delivery) {
        this.retrieval = retrieval;
        this.delivery = delivery;
    }

    @PostMapping("/internal/worker/artifact-tasks/{taskId}/retrieve")
    ApiResponse<ArtifactTaskRetrievalService.Response> retrieve(@PathVariable String taskId,
            @Valid @RequestBody Request request,
            @RequestHeader(value = DurableOutboxDispatcher.DELIVERY_TOKEN_HEADER, defaultValue = "") String token) {
        delivery.requireDelivery(taskId, token);
        return ApiResponse.success(retrieval.retrieve(taskId, request.query(), request.limit()));
    }

    public record Request(@NotBlank @Size(max = 2000) String query, @Min(1) @Max(20) int limit) { }
}
