package com.noteweave.research;

import com.noteweave.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal boundary for future coordinator budget and checkpoint operations. */
@RestController
@RequestMapping("/internal/research-agent")
public class ResearchBudgetCheckpointInternalController {

    private final ResearchBudgetAndCheckpointService service;

    public ResearchBudgetCheckpointInternalController(ResearchBudgetAndCheckpointService service) {
        this.service = service;
    }

    @PostMapping("/budget-reservations")
    ApiResponse<ResearchBudgetAndCheckpointService.ReservationReceipt> reserve(@Valid @RequestBody ReserveRequest request) {
        return ApiResponse.success(service.reserve(new ResearchBudgetAndCheckpointService.ReserveCommand(
                request.researchRunId(), request.taskId(), request.idempotencyKey(), request.reserved()
        )));
    }

    @PostMapping("/budget-reservations/{reservationId}/settle")
    ApiResponse<ResearchBudgetAndCheckpointService.BudgetSnapshot> settle(
            @PathVariable String reservationId, @Valid @RequestBody UsageRequest request
    ) {
        return ApiResponse.success(service.settle(new ResearchBudgetAndCheckpointService.SettleCommand(reservationId, request.values())));
    }

    @PostMapping("/budget-reservations/{reservationId}/release")
    ApiResponse<ResearchBudgetAndCheckpointService.BudgetSnapshot> release(@PathVariable String reservationId) {
        return ApiResponse.success(service.release(reservationId));
    }

    @PostMapping("/checkpoints")
    ApiResponse<ResearchBudgetAndCheckpointService.CheckpointReceipt> checkpoint(@Valid @RequestBody CheckpointRequest request) {
        return ApiResponse.success(service.appendCheckpoint(new ResearchBudgetAndCheckpointService.CheckpointCommand(
                request.researchRunId(), request.waveNo(), request.roundNo(), request.planRevision(), request.entitySetVersion(),
                request.ledgerHash(), request.taskHighWaterMark(), request.candidateHighWaterMark(), request.mergeHighWaterMark(),
                request.budgetSummary(), request.summary()
        )));
    }

    public record ReserveRequest(@NotBlank String researchRunId, @NotBlank String taskId, @NotBlank String idempotencyKey,
                                 @NotEmpty Map<String, Long> reserved) { }
    public record UsageRequest(@NotEmpty Map<String, Long> values) { }
    public record CheckpointRequest(@NotBlank String researchRunId, int waveNo, int roundNo, int planRevision,
                                    int entitySetVersion, @NotBlank String ledgerHash, long taskHighWaterMark,
                                    long candidateHighWaterMark, long mergeHighWaterMark,
                                    Map<String, Object> budgetSummary, Map<String, Object> summary) { }
}
