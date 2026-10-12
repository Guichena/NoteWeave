package com.noteweave.research;

import com.noteweave.common.ApiResponse;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 单元格及其证据与冲突明细。 */
@RestController
class ResearchCellInsightsController {
    private final ResearchCellInsightsService service;
    ResearchCellInsightsController(ResearchCellInsightsService service) { this.service = service; }
    @GetMapping("/api/v2/workspaces/{workspaceId}/research-runs/{runId}/cells")
    ApiResponse<List<Map<String, Object>>> cells(@PathVariable String workspaceId, @PathVariable String runId) {
        return ApiResponse.success(service.cells(workspaceId, runId));
    }
}
