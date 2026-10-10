package com.noteweave.source;

import com.noteweave.common.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/workspaces/{workspaceId}/sources")
public class SourceController {

    private final SourceService sourceService;
    private final SourceReprocessService sourceReprocessService;

    public SourceController(SourceService sourceService, SourceReprocessService sourceReprocessService) {
        this.sourceService = sourceService;
        this.sourceReprocessService = sourceReprocessService;
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

    /** 重新处理单个失败的资料：解析失败从解析阶段开始，只有索引失败时从向量化阶段开始。 */
    @PostMapping("/{sourceId}/reprocess")
    ApiResponse<SourceReprocessResponse> reprocessSource(
            @PathVariable String workspaceId,
            @PathVariable String sourceId
    ) {
        return ApiResponse.success(sourceReprocessService.reprocess(workspaceId, sourceId));
    }
}
