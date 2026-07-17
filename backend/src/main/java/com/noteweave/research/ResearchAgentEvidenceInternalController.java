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

/** Internal-only append boundary; it cannot merge canonical cells. */
@RestController
@RequestMapping("/internal/research-agent")
public class ResearchAgentEvidenceInternalController {
    private final ResearchAgentEvidenceIngestionService service;
    public ResearchAgentEvidenceInternalController(ResearchAgentEvidenceIngestionService service) { this.service = service; }

    @Deprecated(forRemoval = true)
    @PostMapping("/workspace-evidence-batches")
    ApiResponse<ResearchAgentEvidenceIngestionService.BatchReceipt> append(@Valid @RequestBody BatchRequest request) {
        return ApiResponse.success(service.appendWorkspaceEvidence(new ResearchAgentEvidenceIngestionService.EvidenceBatchCommand(
                request.taskId(), request.workerInstanceId(), request.leaseEpoch(), request.fencingToken(),
                request.evidence().stream().map(item -> new ResearchAgentEvidenceIngestionService.WorkspaceEvidence(
                        item.evidenceKey(), item.windowId(), item.sourceId(), item.sourceTitle(), item.searchQuery(), item.readFocus(),
                        item.quoteText(), item.claimText(), item.relationType(), item.supportScore(), item.conflictScore(), item.snapshotStatus()
                )).toList()
        )));
    }
    public record BatchRequest(@NotBlank String taskId, @NotBlank String workerInstanceId, int leaseEpoch, long fencingToken,
                               @NotEmpty List<EvidenceRequest> evidence) { }
    public record EvidenceRequest(@NotBlank String evidenceKey, String windowId, @NotBlank String sourceId, String sourceTitle,
                                  String searchQuery, String readFocus, @NotBlank String quoteText, @NotBlank String claimText,
                                  @NotBlank String relationType, double supportScore, double conflictScore, @NotBlank String snapshotStatus) { }
}
