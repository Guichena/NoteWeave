package com.noteweave.research;

import com.noteweave.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/workspaces/{workspaceId}/research-runs")
public class ResearchRunController {

    private final ResearchRunService researchRunService;

    public ResearchRunController(ResearchRunService researchRunService) {
        this.researchRunService = researchRunService;
    }

    @PostMapping
    ApiResponse<ResearchRunResponse> createResearchRun(
            @PathVariable String workspaceId,
            @Valid @RequestBody CreateResearchRunRequest request
    ) {
        return ApiResponse.success(researchRunService.createRun(workspaceId, request));
    }
}
