package com.noteweave.research;

import com.noteweave.common.ApiResponse;
import com.noteweave.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal-only task lifecycle boundary; worker routes are protected by the internal service filter. */
@RestController
@RequestMapping("/internal/research-agent-tasks")
public class ResearchAgentTaskInternalController {

    private final ResearchAgentTaskService taskService;

    public ResearchAgentTaskInternalController(ResearchAgentTaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    ApiResponse<ResearchAgentTaskService.TaskSnapshot> create(@Valid @RequestBody CreateRequest request) {
        return ApiResponse.success(taskService.createTask(new ResearchAgentTaskService.CreateTaskCommand(
                request.researchRunId(), request.taskKey(), request.idempotencyKey(), request.waveNo(), request.role(),
                request.entityId(), request.branchId(), request.planRevision(), request.entitySetVersion(),
                request.targetCells(), request.budget(),
                request.targetBindings() == null ? null : request.targetBindings().stream()
                        .map(binding -> new ResearchAgentTaskService.TargetCellBinding(binding.cellId(), binding.expectedVersion()))
                        .toList(),
                request.executionContext() == null ? null : new ResearchAgentTaskService.TaskExecutionContext(
                        request.executionContext().providerKey(), request.executionContext().sourcePolicy(), request.executionContext().queryPolicy()
                )
        )));
    }

    @PostMapping("/{taskId}/claim")
    ApiResponse<ResearchAgentTaskService.ClaimedTask> claim(
            @PathVariable String taskId,
            @Valid @RequestBody ClaimRequest request
    ) {
        return ApiResponse.success(taskService.claimTask(new ResearchAgentTaskService.ClaimCommand(
                taskId, request.workerInstanceId(), request.leaseSeconds()
        )));
    }

    @PostMapping("/{taskId}/heartbeat")
    ApiResponse<ResearchAgentTaskService.ClaimedTask> heartbeat(
            @PathVariable String taskId,
            @Valid @RequestBody LeaseRequest request
    ) {
        return ApiResponse.success(taskService.heartbeat(new ResearchAgentTaskService.LeaseCommand(
                taskId, request.workerInstanceId(), request.leaseEpoch(), request.fencingToken(), request.leaseSeconds()
        )));
    }

    @Deprecated(forRemoval = true)
    @PostMapping("/{taskId}/submit")
    ApiResponse<ResearchAgentTaskService.ExecutionReceipt> submit(
            @PathVariable String taskId,
            @Valid @RequestBody SubmitRequest request
    ) {
        return ApiResponse.success(taskService.submitExecution(new ResearchAgentTaskService.SubmitCommand(
                taskId, request.workerInstanceId(), request.leaseEpoch(), request.fencingToken(), request.executionKey(),
                request.terminationReason(), request.usage(), request.traceDigest()
        )));
    }

    @PostMapping("/expire")
    ApiResponse<Integer> expire(HttpServletRequest request) {
        rejectCallerSuppliedLeaseClock(request);
        return ApiResponse.success(taskService.expireLeases());
    }

    private void rejectCallerSuppliedLeaseClock(HttpServletRequest request) {
        if (request.getContentLengthLong() > 0) throw callerClockForbidden();
        try {
            if (request.getInputStream().read() != -1) throw callerClockForbidden();
        } catch (IOException exception) {
            throw callerClockForbidden();
        }
    }

    private BusinessException callerClockForbidden() {
        return new BusinessException(
                "RESEARCH_AGENT_LEASE_CLOCK_OVERRIDE_FORBIDDEN",
                "Lease expiration is evaluated only by the database clock");
    }

    public record CreateRequest(
            @NotBlank String researchRunId, @NotBlank String taskKey, @NotBlank String idempotencyKey,
            int waveNo, @NotBlank String role, @NotBlank String entityId, @NotBlank String branchId,
            int planRevision, int entitySetVersion, @NotEmpty List<String> targetCells, Map<String, Object> budget,
            List<TargetBindingRequest> targetBindings, TaskExecutionContextRequest executionContext
    ) { }
    public record TargetBindingRequest(@NotBlank String cellId, int expectedVersion) { }
    public record TaskExecutionContextRequest(@NotBlank String providerKey, Map<String, Object> sourcePolicy,
                                              Map<String, Object> queryPolicy) { }
    public record ClaimRequest(@NotBlank String workerInstanceId, int leaseSeconds) { }
    public record LeaseRequest(@NotBlank String workerInstanceId, int leaseEpoch, long fencingToken, int leaseSeconds) { }
    public record SubmitRequest(@NotBlank String workerInstanceId, int leaseEpoch, long fencingToken,
                                @NotBlank String executionKey, @NotBlank String terminationReason,
                                Map<String, Object> usage, String traceDigest) { }
}
