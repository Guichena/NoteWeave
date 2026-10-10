package com.noteweave.memory;

import com.noteweave.common.ApiResponse;
import com.noteweave.common.BusinessException;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v2/workspaces/{workspaceId}/memory")
public class MemoryController {

    private final MemorySignalService memorySignalService;
    private final MemoryPromotionService memoryPromotionService;
    private final MemoryCompilerService memoryCompilerService;
    private final MemoryVersionService memoryVersionService;
    private final MemoryOutcomeService memoryOutcomeService;
    private final MemoryShadowRecallService memoryShadowRecallService;
    private final CanonicalMemoryReviewService canonicalMemoryReviewService;
    private final MemoryItemQueryService memoryItemQueryService;
    private final MemoryRuntime memoryRuntime;

    public MemoryController(
            MemorySignalService memorySignalService,
            MemoryPromotionService memoryPromotionService,
            MemoryCompilerService memoryCompilerService,
            MemoryVersionService memoryVersionService,
            MemoryOutcomeService memoryOutcomeService,
            MemoryShadowRecallService memoryShadowRecallService,
            CanonicalMemoryReviewService canonicalMemoryReviewService,
            MemoryItemQueryService memoryItemQueryService,
            MemoryRuntime memoryRuntime
    ) {
        this.memorySignalService = memorySignalService;
        this.memoryPromotionService = memoryPromotionService;
        this.memoryCompilerService = memoryCompilerService;
        this.memoryVersionService = memoryVersionService;
        this.memoryOutcomeService = memoryOutcomeService;
        this.memoryShadowRecallService = memoryShadowRecallService;
        this.canonicalMemoryReviewService = canonicalMemoryReviewService;
        this.memoryItemQueryService = memoryItemQueryService;
        this.memoryRuntime = memoryRuntime;
    }

    @PostMapping("/signals")
    ApiResponse<MemorySignalResponse> createSignal(
            @PathVariable String workspaceId,
            @Valid @RequestBody CreateMemorySignalRequest request
    ) {
        return ApiResponse.success(memorySignalService.createSignal(workspaceId, request));
    }

    @PostMapping("/promotions")
    ApiResponse<MemoryPromotionResponse> promoteSignals(
            @PathVariable String workspaceId,
            @Valid @RequestBody PromoteMemorySignalsRequest request
    ) {
        return ApiResponse.success(memoryPromotionService.promoteSignals(workspaceId, request.signalIds()));
    }

    @GetMapping("/control-pack/chat")
    ApiResponse<MemoryControlPackResponse> getChatControlPack(
            @PathVariable String workspaceId,
            @RequestParam("answer_mode") String answerMode
    ) {
        return ApiResponse.success(memoryCompilerService.compileChatControlPack(workspaceId, answerMode));
    }

    @GetMapping("/control-pack/artifact")
    ApiResponse<MemoryControlPackResponse> getArtifactControlPack(
            @PathVariable String workspaceId,
            @RequestParam("skill_key") String skillKey
    ) {
        return ApiResponse.success(memoryCompilerService.compileArtifactControlPack(workspaceId, skillKey));
    }

    @GetMapping("/control-pack/research")
    ApiResponse<MemoryControlPackResponse> getResearchControlPack(
            @PathVariable String workspaceId,
            @RequestParam("profile_key") String profileKey
    ) {
        return ApiResponse.success(memoryCompilerService.compileResearchControlPack(workspaceId, profileKey));
    }

    @GetMapping("/shadow-recall")
    ApiResponse<MemoryShadowRecallResponse> shadowRecall(@PathVariable String workspaceId) {
        return ApiResponse.success(memoryShadowRecallService.recall(workspaceId));
    }

    @PostMapping("/observations")
    ApiResponse<MemoryObservationResult> createObservation(
            @PathVariable String workspaceId,
            @Valid @RequestBody CreateMemoryObservationRequest request
    ) {
        String provenanceRef = "memory-observation:" + request.observationId().strip();
        return ApiResponse.success(memoryRuntime.observe(new ExecutionObservation(
                request.observationId(), workspaceId, request.scope(), request.slotKey(),
                request.displayText(), "USER_FEEDBACK", provenanceRef)));
    }

    @GetMapping("/items")
    ApiResponse<java.util.List<MemoryItemResponse>> listItems(@PathVariable String workspaceId) {
        return ApiResponse.success(memoryItemQueryService.listItems(workspaceId));
    }

    @GetMapping("/review")
    ApiResponse<java.util.List<MemoryRuntimeReviewItemResponse>> listRuntimeReviews(@PathVariable String workspaceId) {
        return ApiResponse.success(canonicalMemoryReviewService.list(workspaceId));
    }

    @PostMapping("/revisions/{revisionId}/review")
    ApiResponse<MemoryRuntimeRevisionResponse> reviewRuntimeRevision(
            @PathVariable String workspaceId, @PathVariable String revisionId,
            @Valid @RequestBody MemoryReviewDecisionRequest request) {
        return ApiResponse.success(canonicalMemoryReviewService.review(workspaceId, revisionId, request));
    }

    @GetMapping("/objects/{memoryObjectId}/versions")
    ApiResponse<java.util.List<MemoryVersionResponse>> listVersions(
            @PathVariable String workspaceId,
            @PathVariable String memoryObjectId
    ) {
        return ApiResponse.success(memoryVersionService.listVersions(workspaceId, memoryObjectId));
    }

    @GetMapping("/objects/{memoryObjectId}/versions/{memoryVersionId}")
    ApiResponse<MemoryVersionResponse> getVersion(
            @PathVariable String workspaceId,
            @PathVariable String memoryObjectId,
            @PathVariable String memoryVersionId
    ) {
        return ApiResponse.success(memoryVersionService.getVersion(
                workspaceId, memoryObjectId, memoryVersionId));
    }

    @PostMapping("/objects/{memoryObjectId}/versions")
    ApiResponse<Void> retiredLegacyVersionAppend() {
        throw new BusinessException(
                "MEMORY_LEGACY_WRITE_RETIRED",
                "Legacy Memory version 写接口已退役，请使用 canonical revision review",
                HttpStatus.GONE);
    }

    @PostMapping("/objects/{memoryObjectId}/revoke")
    ApiResponse<Void> retiredLegacyObjectRevoke() {
        throw new BusinessException(
                "MEMORY_LEGACY_WRITE_RETIRED",
                "Legacy Memory object 撤销接口已退役，请使用 canonical revision review",
                HttpStatus.GONE);
    }

    @PostMapping("/outcomes")
    ApiResponse<MemoryOutcomeBatchResponse> recordOutcome(
            @PathVariable String workspaceId,
            @Valid @RequestBody CreateMemoryOutcomeRequest request
    ) {
        return ApiResponse.success(memoryOutcomeService.recordOutcome(workspaceId, request));
    }

    @GetMapping("/reviews")
    ApiResponse<Void> retiredLegacyReviewQueue() {
        throw new BusinessException(
                "MEMORY_LEGACY_REVIEW_RETIRED",
                "Legacy Memory review API 已退役，请使用 /memory/review",
                HttpStatus.GONE);
    }

    @PostMapping("/reviews/{reviewKind}/{reviewId}/decisions")
    ApiResponse<Void> retiredLegacyReviewDecision() {
        throw new BusinessException(
                "MEMORY_LEGACY_REVIEW_RETIRED",
                "Legacy Memory review 写接口已退役，请使用 canonical revision review",
                HttpStatus.GONE);
    }

}
