package com.noteweave.research;

import com.noteweave.common.ApiResponse;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 定向反证与修复审计查询接口。 */
@RestController
@RequestMapping("/api/v2/workspaces/{workspaceId}/research-runs/{runId}")
public class ResearchRepairAuditController {
    private final ResearchRepairExecutionService service;
    public ResearchRepairAuditController(ResearchRepairExecutionService service) { this.service = service; }
    @GetMapping("/replans")
    ApiResponse<List<Map<String, Object>>> replans(@PathVariable String workspaceId, @PathVariable String runId) {
        return ApiResponse.success(service.replans(workspaceId, runId));
    }
    @GetMapping("/counter-checks")
    ApiResponse<List<Map<String, Object>>> counterChecks(@PathVariable String workspaceId, @PathVariable String runId) {
        return ApiResponse.success(service.counterChecks(workspaceId, runId));
    }
}
