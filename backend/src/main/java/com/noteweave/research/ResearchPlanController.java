package com.noteweave.research;

import com.noteweave.common.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 研究表格的规划结果，以及确认阶段用户的开始和修改操作。 */
@RestController
@RequestMapping("/api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}/plan")
public class ResearchPlanController {

    private final ResearchPlanningService planningService;

    public ResearchPlanController(ResearchPlanningService planningService) {
        this.planningService = planningService;
    }

    @GetMapping
    ApiResponse<Map<String, Object>> getPlan(@PathVariable String workspaceId, @PathVariable String researchRunId) {
        return ApiResponse.success(planningService.readPlan(workspaceId, researchRunId));
    }

    @PostMapping("/approve")
    ApiResponse<Map<String, Object>> approve(@PathVariable String workspaceId, @PathVariable String researchRunId) {
        planningService.approve(workspaceId, researchRunId);
        return ApiResponse.success(planningService.readPlan(workspaceId, researchRunId));
    }

    @PutMapping
    ApiResponse<Map<String, Object>> revise(
            @PathVariable String workspaceId,
            @PathVariable String researchRunId,
            @RequestBody Map<String, Object> plan
    ) {
        planningService.revise(workspaceId, researchRunId, plan);
        return ApiResponse.success(planningService.readPlan(workspaceId, researchRunId));
    }
}
