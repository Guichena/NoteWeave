package com.noteweave.research;

import com.noteweave.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit, internal-only mode transition for incremental execution runs. */
@RestController
@RequestMapping("/internal/research-runs")
public class ResearchAgentExecutionModeInternalController {

    private final ResearchAgentExecutionModeService executionModeService;

    public ResearchAgentExecutionModeInternalController(ResearchAgentExecutionModeService executionModeService) {
        this.executionModeService = executionModeService;
    }

    @PostMapping("/{researchRunId}/agent-execution-mode")
    ApiResponse<String> setMode(@PathVariable String researchRunId, @Valid @RequestBody ModeRequest request) {
        executionModeService.setMode(researchRunId, request.mode());
        return ApiResponse.success(request.mode().trim().toUpperCase());
    }

    public record ModeRequest(@NotBlank String mode) { }
}
