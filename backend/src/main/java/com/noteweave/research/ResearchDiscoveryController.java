package com.noteweave.research;

import com.noteweave.common.ApiResponse;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 查询范围发现提案，不包含正式证据晋升结果。 */
@RestController
class ResearchDiscoveryController {
    private final ResearchDiscoveryProposalService service;
    ResearchDiscoveryController(ResearchDiscoveryProposalService service) { this.service = service; }
    @GetMapping("/api/v2/workspaces/{workspaceId}/research-runs/{runId}/discovery-proposals")
    ApiResponse<List<Map<String, Object>>> proposals(@PathVariable String workspaceId, @PathVariable String runId) {
        return ApiResponse.success(service.find(workspaceId, runId));
    }
}
