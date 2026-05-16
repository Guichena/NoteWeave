package com.noteweave.artifact.controller;

import com.noteweave.artifact.dto.ArtifactResponse;
import com.noteweave.artifact.dto.ArtifactQuery;
import com.noteweave.artifact.dto.ExportArtifactResponse;
import com.noteweave.artifact.dto.RegenerateArtifactRequest;
import com.noteweave.artifact.dto.UpdateArtifactRequest;
import com.noteweave.artifact.service.ArtifactService;
import com.noteweave.common.api.ApiResponse;
import com.noteweave.common.security.CurrentUserProvider;
import com.noteweave.studio.dto.CreateStudioTaskResponse;
import com.noteweave.studio.service.StudioTaskService;
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
import org.springframework.web.bind.annotation.ModelAttribute;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ArtifactController {

    private final ArtifactService artifactService;
    private final StudioTaskService studioTaskService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/spaces/{spaceId}/artifacts")
    public ApiResponse<List<ArtifactResponse>> listBySpace(@PathVariable Long spaceId, @Valid @ModelAttribute ArtifactQuery query) {
        return ApiResponse.success(artifactService.listBySpace(currentUserProvider.getCurrentUser().userId(), spaceId, query));
    }

    @GetMapping("/chat/sessions/{sessionId}/artifacts")
    public ApiResponse<List<ArtifactResponse>> listBySession(@PathVariable Long sessionId) {
        return ApiResponse.success(artifactService.listBySession(currentUserProvider.getCurrentUser().userId(), sessionId));
    }

    @GetMapping("/artifacts/{artifactId}")
    public ApiResponse<ArtifactResponse> get(@PathVariable Long artifactId) {
        return ApiResponse.success(artifactService.get(currentUserProvider.getCurrentUser().userId(), artifactId));
    }

    @PutMapping("/artifacts/{artifactId}")
    public ApiResponse<ArtifactResponse> update(@PathVariable Long artifactId, @Valid @RequestBody UpdateArtifactRequest request) {
        return ApiResponse.success(artifactService.update(currentUserProvider.getCurrentUser().userId(), artifactId, request));
    }

    @DeleteMapping("/artifacts/{artifactId}")
    public ApiResponse<Void> archive(@PathVariable Long artifactId) {
        artifactService.archive(currentUserProvider.getCurrentUser().userId(), artifactId);
        return ApiResponse.success(null);
    }

    @PostMapping("/artifacts/{artifactId}/regenerate")
    public ApiResponse<CreateStudioTaskResponse> regenerate(
            @PathVariable Long artifactId,
            @RequestBody(required = false) RegenerateArtifactRequest request
    ) {
        return ApiResponse.success(studioTaskService.regenerate(currentUserProvider.getCurrentUser().userId(), artifactId, request));
    }

    @PostMapping("/artifacts/{artifactId}/generate")
    public ApiResponse<CreateStudioTaskResponse> generate(
            @PathVariable Long artifactId,
            @RequestBody(required = false) RegenerateArtifactRequest request
    ) {
        return ApiResponse.success(studioTaskService.regenerate(currentUserProvider.getCurrentUser().userId(), artifactId, request));
    }

    @GetMapping("/artifacts/{artifactId}/export")
    public ApiResponse<ExportArtifactResponse> export(@PathVariable Long artifactId, @RequestParam("format") String format) {
        return ApiResponse.success(artifactService.export(currentUserProvider.getCurrentUser().userId(), artifactId, format));
    }
}
