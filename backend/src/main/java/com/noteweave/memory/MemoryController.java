package com.noteweave.memory;

import com.noteweave.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/workspaces/{workspaceId}/memory")
public class MemoryController {

    private final MemorySignalService memorySignalService;
    private final MemoryPromotionService memoryPromotionService;
    private final MemoryCompilerService memoryCompilerService;
    private final MemoryVersionService memoryVersionService;
    private final MemoryOutcomeService memoryOutcomeService;
    private final MemoryReviewService memoryReviewService;
    private final MemoryShadowRecallService memoryShadowRecallService;
    private final CanonicalMemoryReviewService canonicalMemoryReviewService;

    public MemoryController(
            MemorySignalService memorySignalService,
            MemoryPromotionService memoryPromotionService,
            MemoryCompilerService memoryCompilerService,
            MemoryVersionService memoryVersionService,
            MemoryOutcomeService memoryOutcomeService,
            MemoryReviewService memoryReviewService,
            MemoryShadowRecallService memoryShadowRecallService,
            CanonicalMemoryReviewService canonicalMemoryReviewService
    ) {
        this.memorySignalService = memorySignalService;
        this.memoryPromotionService = memoryPromotionService;
        this.memoryCompilerService = memoryCompilerService;
        this.memoryVersionService = memoryVersionService;
        this.memoryOutcomeService = memoryOutcomeService;
        this.memoryReviewService = memoryReviewService;
        this.memoryShadowRecallService = memoryShadowRecallService;
        this.canonicalMemoryReviewService = canonicalMemoryReviewService;
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
    ApiResponse<MemoryVersionResponse> appendVersion(
            @PathVariable String workspaceId,
            @PathVariable String memoryObjectId,
            @Valid @RequestBody AppendMemoryVersionRequest request
    ) {
        return ApiResponse.success(memoryVersionService.appendVersion(
                workspaceId, memoryObjectId, request));
    }

    @PostMapping("/objects/{memoryObjectId}/revoke")
    ApiResponse<MemoryVersionResponse> revoke(
            @PathVariable String workspaceId,
            @PathVariable String memoryObjectId
    ) {
        return ApiResponse.success(memoryVersionService.revoke(workspaceId, memoryObjectId));
    }

    @PostMapping("/outcomes")
    ApiResponse<MemoryOutcomeBatchResponse> recordOutcome(
            @PathVariable String workspaceId,
            @Valid @RequestBody CreateMemoryOutcomeRequest request
    ) {
        return ApiResponse.success(memoryOutcomeService.recordOutcome(workspaceId, request));
    }

    @GetMapping("/reviews")
    ApiResponse<java.util.List<MemoryReviewItemResponse>> listReviews(
            @PathVariable String workspaceId,
            @RequestParam(name = "kind", defaultValue = "ALL") String kind,
            @RequestParam(name = "limit", defaultValue = "50") int limit
    ) {
        return ApiResponse.success(memoryReviewService.listQueue(workspaceId, kind, limit));
    }

    @PostMapping("/reviews/{reviewKind}/{reviewId}/decisions")
    ApiResponse<MemoryReviewDecisionResponse> decideReview(
            @PathVariable String workspaceId,
            @PathVariable String reviewKind,
            @PathVariable String reviewId,
            @Valid @RequestBody MemoryReviewDecisionRequest request
    ) {
        return ApiResponse.success(memoryReviewService.decide(
                workspaceId, reviewKind, reviewId, request));
    }
}
