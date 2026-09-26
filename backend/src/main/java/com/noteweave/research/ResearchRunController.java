package com.noteweave.research;

import com.noteweave.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/workspaces/{workspaceId}/research-runs")
public class ResearchRunController {

    private final ResearchRunService researchRunService;
    private final ResearchCollectionService researchCollectionService;

    public ResearchRunController(
            ResearchRunService researchRunService,
            ResearchCollectionService researchCollectionService
    ) {
        this.researchRunService = researchRunService;
        this.researchCollectionService = researchCollectionService;
    }

    @GetMapping
    ApiResponse<List<ResearchRunSummaryResponse>> listResearchRuns(
            @PathVariable String workspaceId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        return ApiResponse.success(researchRunService.listRuns(workspaceId, limit, offset));
    }

    @PostMapping
    ApiResponse<ResearchRunResponse> createResearchRun(
            @PathVariable String workspaceId,
            @Valid @RequestBody CreateResearchRunRequest request
    ) {
        return ApiResponse.success(researchRunService.createRun(workspaceId, request));
    }

    @GetMapping("/{researchRunId}")
    ApiResponse<ResearchRunDetailResponse> getResearchRun(
            @PathVariable String workspaceId,
            @PathVariable String researchRunId
    ) {
        return ApiResponse.success(researchRunService.getRunDetail(workspaceId, researchRunId));
    }

    @GetMapping("/{researchRunId}/evidence")
    ApiResponse<ResearchEvidenceManifestResponse> getResearchEvidence(
            @PathVariable String workspaceId,
            @PathVariable String researchRunId
    ) {
        return ApiResponse.success(researchRunService.evidenceManifest(workspaceId, researchRunId));
    }

    @GetMapping("/{researchRunId}/collection")
    ApiResponse<ResearchCollectionResponse> getResearchCollection(
            @PathVariable String workspaceId,
            @PathVariable String researchRunId
    ) {
        return ApiResponse.success(researchCollectionService.get(workspaceId, researchRunId));
    }

    @GetMapping("/{researchRunId}/checkpoints")
    ApiResponse<List<ResearchCheckpointSummaryResponse>> listResearchCheckpoints(
            @PathVariable String workspaceId,
            @PathVariable String researchRunId
    ) {
        return ApiResponse.success(researchRunService.listCheckpoints(workspaceId, researchRunId));
    }

    @GetMapping("/{researchRunId}/checkpoints/{checkpointNo}")
    ApiResponse<ResearchCheckpointResponse> getResearchCheckpoint(
            @PathVariable String workspaceId,
            @PathVariable String researchRunId,
            @PathVariable int checkpointNo
    ) {
        return ApiResponse.success(researchRunService.getCheckpoint(workspaceId, researchRunId, checkpointNo));
    }

    @PostMapping("/{researchRunId}/resume-from-checkpoint/{checkpointNo}")
    ApiResponse<ResearchRunResponse> resumeResearchRunFromCheckpoint(
            @PathVariable String workspaceId,
            @PathVariable String researchRunId,
            @PathVariable int checkpointNo,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "AUTO") String resumeMode
    ) {
        return ApiResponse.success(researchRunService.resumeFromCheckpoint(
                workspaceId, researchRunId, checkpointNo, resumeMode));
    }

    @PostMapping("/{researchRunId}/save-report-as-source")
    ApiResponse<SaveResearchReportSourceResponse> saveReportAsSource(
            @PathVariable String workspaceId,
            @PathVariable String researchRunId
    ) {
        return ApiResponse.success(researchRunService.saveReportAsSource(workspaceId, researchRunId));
    }
}
