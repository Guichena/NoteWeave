package com.noteweave.workspace;

import com.noteweave.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api/v2/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final UpdateWorkspaceWikiSettingsUseCase updateWikiSettingsUseCase;
    private final WorkspaceMembershipService membershipService;

    public WorkspaceController(
            WorkspaceService workspaceService,
            UpdateWorkspaceWikiSettingsUseCase updateWikiSettingsUseCase,
            WorkspaceMembershipService membershipService
    ) {
        this.workspaceService = workspaceService;
        this.updateWikiSettingsUseCase = updateWikiSettingsUseCase;
        this.membershipService = membershipService;
    }

    @PostMapping
    ApiResponse<WorkspaceResponse> createWorkspace(@Valid @RequestBody CreateWorkspaceRequest request) {
        return ApiResponse.success(workspaceService.createWorkspace(request));
    }

    @GetMapping
    ApiResponse<List<WorkspaceResponse>> listWorkspaces() {
        return ApiResponse.success(workspaceService.listWorkspaces());
    }

    @GetMapping("/{workspaceId}/wiki-settings")
    ApiResponse<WorkspaceWikiSettingsResponse> getWikiSettings(@PathVariable String workspaceId) {
        return ApiResponse.success(workspaceService.getWikiSettings(workspaceId));
    }

    @PutMapping("/{workspaceId}/wiki-settings")
    ApiResponse<WorkspaceWikiSettingsResponse> updateWikiSettings(
            @PathVariable String workspaceId,
            @RequestBody UpdateWorkspaceWikiSettingsRequest request
    ) {
        return ApiResponse.success(updateWikiSettingsUseCase.execute(workspaceId, request));
    }

    @GetMapping("/{workspaceId}/retrieval-settings")
    ApiResponse<WorkspaceRetrievalSettingsResponse> getRetrievalSettings(@PathVariable String workspaceId) {
        return ApiResponse.success(workspaceService.getRetrievalSettings(workspaceId));
    }

    @PutMapping("/{workspaceId}/retrieval-settings")
    ApiResponse<WorkspaceRetrievalSettingsResponse> updateRetrievalSettings(
            @PathVariable String workspaceId,
            @Valid @RequestBody UpdateWorkspaceRetrievalSettingsRequest request
    ) {
        return ApiResponse.success(workspaceService.updateRetrievalSettings(workspaceId, request));
    }

    @GetMapping("/{workspaceId}/members")
    ApiResponse<List<WorkspaceMemberResponse>> listMembers(@PathVariable String workspaceId) {
        return ApiResponse.success(membershipService.listMembers(workspaceId));
    }

    @PutMapping("/{workspaceId}/members/{userId}")
    ApiResponse<WorkspaceMemberResponse> putMember(
            @PathVariable String workspaceId,
            @PathVariable String userId,
            @Valid @RequestBody UpdateWorkspaceMemberRequest request
    ) {
        return ApiResponse.success(membershipService.putMember(workspaceId, userId, request));
    }

    @DeleteMapping("/{workspaceId}/members/{userId}")
    ApiResponse<Void> removeMember(@PathVariable String workspaceId, @PathVariable String userId) {
        membershipService.removeMember(workspaceId, userId);
        return ApiResponse.success(null);
    }
}
