package com.noteweave.personal.question.controller;

import com.noteweave.common.api.ApiResponse;
import com.noteweave.common.security.CurrentUserProvider;
import com.noteweave.personal.question.dto.CreateResearchQuestionRequest;
import com.noteweave.personal.question.dto.ResearchQuestionOverviewResponse;
import com.noteweave.personal.question.dto.ResearchQuestionResponse;
import com.noteweave.personal.question.dto.ResearchQuestionWorkspaceResponse;
import com.noteweave.personal.question.dto.UpdateResearchQuestionRequest;
import com.noteweave.personal.question.service.ResearchQuestionOverviewService;
import com.noteweave.personal.question.service.ResearchQuestionService;
import com.noteweave.personal.question.service.ResearchQuestionWorkspaceService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/personal/research-questions")
@RequiredArgsConstructor
public class ResearchQuestionController {

    private final ResearchQuestionService researchQuestionService;
    private final ResearchQuestionOverviewService researchQuestionOverviewService;
    private final ResearchQuestionWorkspaceService researchQuestionWorkspaceService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping
    public ApiResponse<ResearchQuestionResponse> create(@Valid @RequestBody CreateResearchQuestionRequest request) {
        return ApiResponse.success(researchQuestionService.create(currentUserProvider.getCurrentUserId(), request));
    }

    @GetMapping
    public ApiResponse<List<ResearchQuestionResponse>> listByProject(@RequestParam Long researchProjectId) {
        return ApiResponse.success(
                researchQuestionService.listByProject(currentUserProvider.getCurrentUserId(), researchProjectId));
    }

    @GetMapping("/{questionId}")
    public ApiResponse<ResearchQuestionResponse> get(@PathVariable Long questionId) {
        return ApiResponse.success(researchQuestionService.get(currentUserProvider.getCurrentUserId(), questionId));
    }

    @PostMapping("/{questionId}/overview:generate")
    public ApiResponse<ResearchQuestionOverviewResponse> generateOverview(@PathVariable Long questionId) {
        return ApiResponse.success(
                researchQuestionOverviewService.generate(currentUserProvider.getCurrentUserId(), questionId));
    }

    @GetMapping("/{questionId}/overview")
    public ApiResponse<ResearchQuestionOverviewResponse> getOverview(@PathVariable Long questionId) {
        return ApiResponse.success(
                researchQuestionOverviewService.getLatest(currentUserProvider.getCurrentUserId(), questionId));
    }

    @GetMapping("/{questionId}/workspace")
    public ApiResponse<ResearchQuestionWorkspaceResponse> getWorkspace(@PathVariable Long questionId) {
        return ApiResponse.success(
                researchQuestionWorkspaceService.getWorkspace(currentUserProvider.getCurrentUserId(), questionId));
    }

    @PutMapping("/{questionId}")
    public ApiResponse<ResearchQuestionResponse> update(
            @PathVariable Long questionId,
            @Valid @RequestBody UpdateResearchQuestionRequest request
    ) {
        return ApiResponse.success(
                researchQuestionService.update(currentUserProvider.getCurrentUserId(), questionId, request));
    }

    @DeleteMapping("/{questionId}")
    public ApiResponse<Void> delete(@PathVariable Long questionId) {
        researchQuestionService.archive(currentUserProvider.getCurrentUserId(), questionId);
        return ApiResponse.success(null);
    }
}
