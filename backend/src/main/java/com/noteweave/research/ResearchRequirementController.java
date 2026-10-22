package com.noteweave.research;

import com.noteweave.common.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 在既有工作台访问校验之后展示权威需求、累计事实和缺项。 */
@RestController
@RequestMapping("/api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}/requirements")
class ResearchRequirementController {
    private final ResearchRequirementQueryService requirements;

    ResearchRequirementController(ResearchRequirementQueryService requirements) {
        this.requirements = requirements;
    }

    @GetMapping
    ApiResponse<Map<String, Object>> get(@PathVariable String workspaceId, @PathVariable String researchRunId) {
        return ApiResponse.success(requirements.get(workspaceId, researchRunId));
    }
}
