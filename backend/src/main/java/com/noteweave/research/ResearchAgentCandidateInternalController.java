package com.noteweave.research;

import com.noteweave.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal candidate admission; Backend retains the only merge authority. */
@RestController
@RequestMapping("/internal/research-agent")
public class ResearchAgentCandidateInternalController {
    private final ResearchAgentCandidateIngressService service;
    public ResearchAgentCandidateInternalController(ResearchAgentCandidateIngressService service) { this.service = service; }
    @Deprecated(forRemoval = true)
    @PostMapping("/candidate-batches")
    ApiResponse<ResearchAgentCandidateIngressService.CandidateBatchReceipt> append(@Valid @RequestBody BatchRequest request) {
        return ApiResponse.success(service.appendAndVerify(new ResearchAgentCandidateIngressService.CandidateBatchCommand(
                request.taskId(), request.workerInstanceId(), request.leaseEpoch(), request.fencingToken(), request.executionId(),
                request.candidates().stream().map(item -> new ResearchAgentCandidateIngressService.CandidateProposal(
                        item.candidateId(), item.idempotencyKey(), item.cellKey(), item.baseCellVersion(), item.candidateValue(),
                        item.evidenceKeys(), item.confidence())).toList())));
    }
    public record BatchRequest(@NotBlank String taskId, @NotBlank String workerInstanceId, int leaseEpoch, long fencingToken,
                               @NotBlank String executionId, @NotEmpty List<CandidateRequest> candidates) { }
    public record CandidateRequest(@NotBlank String candidateId, @NotBlank String idempotencyKey, @NotBlank String cellKey,
                                   int baseCellVersion, @NotBlank String candidateValue, @NotEmpty List<String> evidenceKeys,
                                   double confidence) { }
}
