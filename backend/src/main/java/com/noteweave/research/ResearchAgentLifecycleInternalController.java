package com.noteweave.research;

import com.noteweave.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal Worker/Coordinator boundary for cancellation and sanitized delivery-failure audit. */
@RestController
@RequestMapping("/internal/research-agent")
public class ResearchAgentLifecycleInternalController {
    private final ResearchAgentLifecycleService lifecycleService;

    public ResearchAgentLifecycleInternalController(ResearchAgentLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @PostMapping("/runs/{runId}/cancel")
    ApiResponse<ResearchAgentLifecycleService.CancelReceipt> cancel(@PathVariable String runId, @Valid @RequestBody CancelRequest request) {
        return ApiResponse.success(lifecycleService.cancelRun(runId, request.reason()));
    }

    @PostMapping("/delivery-failures")
    ApiResponse<ResearchAgentLifecycleService.DeliveryFailureReceipt> failure(@Valid @RequestBody DeliveryFailureRequest request) {
        return ApiResponse.success(lifecycleService.recordDeliveryFailure(new ResearchAgentLifecycleService.DeliveryFailureCommand(
                request.researchRunId(), request.taskId(), request.outboxId(), request.failureKey(), request.reasonCode(), request.traceDigest(), request.deliveryAttempt()
        )));
    }

    @PostMapping("/delivery-failures/{failureId}/redrive")
    ApiResponse<ResearchAgentLifecycleService.RedriveReceipt> redrive(@PathVariable String failureId) {
        return ApiResponse.success(lifecycleService.redriveDeliveryFailure(failureId));
    }

    public record CancelRequest(@NotBlank String reason) { }
    public record DeliveryFailureRequest(@NotBlank String researchRunId, @NotBlank String taskId, String outboxId,
                                         @NotBlank String failureKey, @NotBlank String reasonCode, @NotBlank String traceDigest,
                                         int deliveryAttempt) { }
}
