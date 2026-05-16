package com.noteweave.personal.project.controller;

import com.noteweave.common.api.ApiResponse;
import com.noteweave.common.security.CurrentUserProvider;
import com.noteweave.personal.project.dto.CreateResearchProjectRequest;
import com.noteweave.personal.project.dto.ResearchProjectResponse;
import com.noteweave.personal.project.dto.UpdateResearchProjectRequest;
import com.noteweave.personal.project.service.ResearchProjectService;
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
@RequestMapping("/api/v1/personal/research-projects")
@RequiredArgsConstructor
public class ResearchProjectController {

    private final ResearchProjectService researchProjectService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping
    public ApiResponse<ResearchProjectResponse> create(@Valid @RequestBody CreateResearchProjectRequest request) {
        return ApiResponse.success(researchProjectService.create(currentUserProvider.getCurrentUserId(), request));
    }

    @GetMapping
    public ApiResponse<List<ResearchProjectResponse>> listMine() {
        return ApiResponse.success(researchProjectService.listMine(currentUserProvider.getCurrentUserId()));
    }

    @GetMapping("/{projectId}")
    public ApiResponse<ResearchProjectResponse> get(@PathVariable Long projectId) {
        return ApiResponse.success(researchProjectService.get(currentUserProvider.getCurrentUserId(), projectId));
    }

    @PutMapping("/{projectId}")
    public ApiResponse<ResearchProjectResponse> update(
            @PathVariable Long projectId,
            @Valid @RequestBody UpdateResearchProjectRequest request
    ) {
        return ApiResponse.success(researchProjectService.update(currentUserProvider.getCurrentUserId(), projectId, request));
    }

    @DeleteMapping("/{projectId}")
    public ApiResponse<Void> delete(@PathVariable Long projectId) {
        researchProjectService.archive(currentUserProvider.getCurrentUserId(), projectId);
        return ApiResponse.success(null);
    }
}
