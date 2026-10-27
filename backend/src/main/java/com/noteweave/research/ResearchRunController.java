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

    /**
     * 报告 Markdown 正式下载：服务端执行依赖闭包校验后下发字节，
     * 前端不再从缓存正文生成正式导出。文件名做安全转义并声明附件语义。
     */
    @GetMapping("/{researchRunId}/report/markdown")
    org.springframework.http.ResponseEntity<byte[]> downloadReportMarkdown(
            @PathVariable String workspaceId,
            @PathVariable String researchRunId
    ) {
        ResearchArtifactService.ResearchReportDownload download =
                researchRunService.downloadReportMarkdown(workspaceId, researchRunId);
        String safeFileName = download.fileName()
                .replace("\r", "_").replace("\n", "_").replace("\"", "_").replace("'", "_");
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.attachment()
                                .filename(safeFileName, java.nio.charset.StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(org.springframework.http.MediaType.parseMediaType(download.contentType()))
                .body(download.content());
    }
}
