package com.noteweave.admin.controller;

import com.noteweave.common.api.ApiResponse;
import com.noteweave.common.security.CurrentUserProvider;
import com.noteweave.permission.service.ResourceAccessService;
import com.noteweave.rageval.dto.RagEvalCaseResponse;
import com.noteweave.rageval.dto.RagEvalResultResponse;
import com.noteweave.rageval.dto.RagEvalRunResponse;
import com.noteweave.rageval.dto.StartRagEvalRunRequest;
import com.noteweave.rageval.dto.UpsertRagEvalCaseRequest;
import com.noteweave.rageval.service.RagEvaluationService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminRagEvalController {

    private final CurrentUserProvider currentUserProvider;
    private final ResourceAccessService resourceAccessService;
    private final RagEvaluationService ragEvaluationService;

    @GetMapping("/spaces/{spaceId}/rag-eval-cases")
    public ApiResponse<List<RagEvalCaseResponse>> listCases(@PathVariable Long spaceId) {
        resourceAccessService.requireAdmin(currentUserProvider.getCurrentUserId());
        return ApiResponse.success(ragEvaluationService.listCases(currentUserProvider.getCurrentUserId(), spaceId));
    }

    @PostMapping("/spaces/{spaceId}/rag-eval-cases")
    public ApiResponse<RagEvalCaseResponse> createCase(
            @PathVariable Long spaceId,
            @Valid @RequestBody UpsertRagEvalCaseRequest request
    ) {
        resourceAccessService.requireAdmin(currentUserProvider.getCurrentUserId());
        return ApiResponse.success(ragEvaluationService.upsertCase(currentUserProvider.getCurrentUserId(), spaceId, null, request));
    }

    @PutMapping("/rag-eval-cases/{caseId}")
    public ApiResponse<RagEvalCaseResponse> updateCase(
            @PathVariable Long caseId,
            @Valid @RequestBody UpsertRagEvalCaseRequest request
    ) {
        Long userId = currentUserProvider.getCurrentUserId();
        resourceAccessService.requireAdmin(userId);
        return ApiResponse.success(ragEvaluationService.upsertCase(userId, resolveSpaceId(caseId, userId), caseId, request));
    }

    @PostMapping("/spaces/{spaceId}/rag-eval-runs")
    public ApiResponse<RagEvalRunResponse> startRun(
            @PathVariable Long spaceId,
            @Valid @RequestBody StartRagEvalRunRequest request
    ) {
        resourceAccessService.requireAdmin(currentUserProvider.getCurrentUserId());
        return ApiResponse.success(ragEvaluationService.startRun(currentUserProvider.getCurrentUserId(), spaceId, request));
    }

    @GetMapping("/rag-eval-runs/{runId}")
    public ApiResponse<RagEvalRunResponse> getRun(@PathVariable Long runId) {
        resourceAccessService.requireAdmin(currentUserProvider.getCurrentUserId());
        return ApiResponse.success(ragEvaluationService.getRun(currentUserProvider.getCurrentUserId(), runId));
    }

    @GetMapping("/rag-eval-runs/{runId}/results")
    public ApiResponse<List<RagEvalResultResponse>> listResults(@PathVariable Long runId) {
        resourceAccessService.requireAdmin(currentUserProvider.getCurrentUserId());
        return ApiResponse.success(ragEvaluationService.listResults(currentUserProvider.getCurrentUserId(), runId));
    }

    private Long resolveSpaceId(Long caseId, Long userId) {
        return ragEvaluationService.findCaseSpaceId(userId, caseId);
    }
}
