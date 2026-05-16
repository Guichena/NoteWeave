package com.noteweave.personal.source.controller;

import com.noteweave.common.api.ApiResponse;
import com.noteweave.common.security.CurrentUserProvider;
import com.noteweave.personal.compiler.dto.CompileSourceResponse;
import com.noteweave.personal.compiler.service.WikiCompilerService;
import com.noteweave.personal.source.dto.AddTextSourceRequest;
import com.noteweave.personal.source.dto.AddUrlSourceRequest;
import com.noteweave.personal.source.dto.SourceResponse;
import com.noteweave.personal.source.dto.UploadSourceRequest;
import com.noteweave.personal.source.service.SourceService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/personal")
@RequiredArgsConstructor
public class SourceController {

    private final SourceService sourceService;
    private final WikiCompilerService wikiCompilerService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping("/research-projects/{projectId}/sources/upload")
    public ApiResponse<SourceResponse> uploadFile(
            @PathVariable Long projectId,
            @RequestPart("file") MultipartFile file,
            @Valid @RequestPart(value = "request", required = false) UploadSourceRequest request
    ) {
        return ApiResponse.success(sourceService.uploadFile(currentUserProvider.getCurrentUserId(), projectId, file, request));
    }

    @PostMapping("/research-projects/{projectId}/sources/url")
    public ApiResponse<SourceResponse> addUrl(
            @PathVariable Long projectId,
            @Valid @RequestBody AddUrlSourceRequest request
    ) {
        return ApiResponse.success(sourceService.addUrl(currentUserProvider.getCurrentUserId(), projectId, request));
    }

    @PostMapping("/research-projects/{projectId}/sources/text")
    public ApiResponse<SourceResponse> addText(
            @PathVariable Long projectId,
            @Valid @RequestBody AddTextSourceRequest request
    ) {
        return ApiResponse.success(sourceService.addText(currentUserProvider.getCurrentUserId(), projectId, request));
    }

    @GetMapping("/research-projects/{projectId}/sources")
    public ApiResponse<List<SourceResponse>> list(@PathVariable Long projectId) {
        return ApiResponse.success(sourceService.list(currentUserProvider.getCurrentUserId(), projectId));
    }

    @GetMapping("/sources/{sourceId}")
    public ApiResponse<SourceResponse> get(@PathVariable Long sourceId) {
        return ApiResponse.success(sourceService.get(currentUserProvider.getCurrentUserId(), sourceId));
    }

    @PostMapping("/sources/{sourceId}/import")
    public ApiResponse<SourceResponse> triggerImport(@PathVariable Long sourceId) {
        return ApiResponse.success(sourceService.triggerImport(currentUserProvider.getCurrentUserId(), sourceId));
    }

    @PostMapping("/sources/{sourceId}/compile")
    public ApiResponse<CompileSourceResponse> compile(@PathVariable Long sourceId) {
        return ApiResponse.success(wikiCompilerService.compileSource(currentUserProvider.getCurrentUserId(), sourceId));
    }

    @DeleteMapping("/sources/{sourceId}")
    public ApiResponse<Void> delete(@PathVariable Long sourceId) {
        sourceService.delete(currentUserProvider.getCurrentUserId(), sourceId);
        return ApiResponse.success(null);
    }
}
