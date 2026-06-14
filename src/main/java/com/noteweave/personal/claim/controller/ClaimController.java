package com.noteweave.personal.claim.controller;

import com.noteweave.common.api.ApiResponse;
import com.noteweave.common.security.CurrentUserProvider;
import com.noteweave.personal.claim.dto.ClaimResponse;
import com.noteweave.personal.claim.dto.ConceptComparisonResponse;
import com.noteweave.personal.claim.dto.CreateClaimRequest;
import com.noteweave.personal.claim.dto.UpdateClaimRequest;
import com.noteweave.personal.claim.service.ClaimService;
import com.noteweave.personal.claim.service.ConceptComparisonService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/personal")
@RequiredArgsConstructor
public class ClaimController {

    private final ClaimService claimService;
    private final ConceptComparisonService conceptComparisonService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping("/claims")
    public ApiResponse<ClaimResponse> create(@Valid @RequestBody CreateClaimRequest request) {
        return ApiResponse.success(claimService.create(currentUserProvider.getCurrentUserId(), request));
    }

    @GetMapping("/research-questions/{questionId}/claims")
    public ApiResponse<List<ClaimResponse>> listByQuestion(@PathVariable Long questionId) {
        return ApiResponse.success(claimService.listByQuestion(currentUserProvider.getCurrentUserId(), questionId));
    }

    @GetMapping("/claims/{claimId}")
    public ApiResponse<ClaimResponse> get(@PathVariable Long claimId) {
        return ApiResponse.success(claimService.get(currentUserProvider.getCurrentUserId(), claimId));
    }

    @PutMapping("/claims/{claimId}")
    public ApiResponse<ClaimResponse> update(
            @PathVariable Long claimId,
            @Valid @RequestBody UpdateClaimRequest request
    ) {
        return ApiResponse.success(claimService.update(currentUserProvider.getCurrentUserId(), claimId, request));
    }

    @DeleteMapping("/claims/{claimId}")
    public ApiResponse<Void> delete(@PathVariable Long claimId) {
        claimService.archive(currentUserProvider.getCurrentUserId(), claimId);
        return ApiResponse.success(null);
    }

    @GetMapping("/concept-cards/{conceptCardId}/cross-question-comparison")
    public ApiResponse<ConceptComparisonResponse> compareConceptAcrossQuestions(@PathVariable Long conceptCardId) {
        return ApiResponse.success(
                conceptComparisonService.compareAcrossQuestions(currentUserProvider.getCurrentUserId(), conceptCardId));
    }
}
