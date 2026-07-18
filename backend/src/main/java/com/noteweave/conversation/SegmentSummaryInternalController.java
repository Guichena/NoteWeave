package com.noteweave.conversation;

import com.noteweave.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/conversation-segments")
public class SegmentSummaryInternalController {

    private final SegmentSummaryPromotionService promotionService;

    public SegmentSummaryInternalController(SegmentSummaryPromotionService promotionService) {
        this.promotionService = promotionService;
    }

    @PostMapping("/{segmentId}/summary-revisions/{revisionId}/promote")
    ApiResponse<SegmentSummaryPromotionService.PromotionResult> promote(
            @PathVariable String segmentId,
            @PathVariable String revisionId,
            @Valid @RequestBody PromoteSegmentSummaryRequest request
    ) {
        return ApiResponse.success(promotionService.promote(segmentId, revisionId, request));
    }

    @DeleteMapping("/{segmentId}/summary-revisions/{revisionId}")
    ApiResponse<SegmentSummaryPromotionService.PromotionResult> delete(
            @PathVariable String segmentId,
            @PathVariable String revisionId
    ) {
        return ApiResponse.success(promotionService.delete(segmentId, revisionId));
    }
}
