package com.noteweave.source;

import com.noteweave.common.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/workspaces/{workspaceId}/sources")
public class SourceController {

    private final SourceService sourceService;

    public SourceController(SourceService sourceService) {
        this.sourceService = sourceService;
    }

    @GetMapping
    ApiResponse<List<SourceResponse>> listSources(@PathVariable String workspaceId) {
        return ApiResponse.success(sourceService.listSources(workspaceId));
    }

    @DeleteMapping("/{sourceId}")
    ApiResponse<DeleteSourceResponse> deleteSource(
            @PathVariable String workspaceId,
            @PathVariable String sourceId
    ) {
        return ApiResponse.success(sourceService.deleteSource(workspaceId, sourceId));
    }
}
