package com.noteweave.retrieval.projection;

import com.noteweave.common.ApiResponse;
import com.noteweave.retrieval.projection.RetrievalBackfillService.BackfillResult;
import com.noteweave.retrieval.projection.RetrievalBackfillService.RetrievalStatus;
import com.noteweave.security.WorkspaceAccessGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/workspaces/{workspaceId}/retrieval-indexes")
public class RetrievalManagementController {
    private final RetrievalBackfillService backfillService;
    private final WorkspaceAccessGuard accessGuard;
    private final RetrievalReleaseGateService releaseGateService;
    private final RetrievalQualityReceiptService qualityReceiptService;

    public RetrievalManagementController(
            RetrievalBackfillService backfillService,
            WorkspaceAccessGuard accessGuard,
            RetrievalReleaseGateService releaseGateService,
            RetrievalQualityReceiptService qualityReceiptService
    ) {
        this.backfillService = backfillService;
        this.accessGuard = accessGuard;
        this.releaseGateService = releaseGateService;
        this.qualityReceiptService = qualityReceiptService;
    }

    @GetMapping
    ApiResponse<RetrievalStatus> status(@PathVariable String workspaceId) {
        accessGuard.requireMember(workspaceId);
        return ApiResponse.success(backfillService.status(workspaceId));
    }

    @PostMapping("/rebuild")
    ApiResponse<BackfillResult> rebuild(@PathVariable String workspaceId) {
        accessGuard.requireOwner(workspaceId);
        return ApiResponse.success(backfillService.rebuildWorkspace(workspaceId));
    }

    @GetMapping("/release-gate")
    ApiResponse<RetrievalReleaseGateService.GateResult> releaseGate(@PathVariable String workspaceId) {
        accessGuard.requireOwner(workspaceId);
        return ApiResponse.success(releaseGateService.evaluate(workspaceId));
    }

    @PostMapping("/quality-receipts")
    ApiResponse<RetrievalQualityReceiptService.Receipt> recordQualityReceipt(
            @PathVariable String workspaceId,
            @org.springframework.web.bind.annotation.RequestBody
            RetrievalQualityReceiptService.RecordRequest request
    ) {
        accessGuard.requireOwner(workspaceId);
        return ApiResponse.success(qualityReceiptService.record(workspaceId, request));
    }
}
