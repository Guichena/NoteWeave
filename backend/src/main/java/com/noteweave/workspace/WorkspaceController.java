package com.noteweave.workspace;

import com.noteweave.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @PostMapping
    ApiResponse<WorkspaceResponse> createWorkspace(@Valid @RequestBody CreateWorkspaceRequest request) {
        return ApiResponse.success(workspaceService.createWorkspace(request));
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
        return ApiResponse.success(workspaceService.updateWikiSettings(workspaceId, request));
    }
}
