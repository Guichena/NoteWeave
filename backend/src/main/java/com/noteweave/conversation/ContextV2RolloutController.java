package com.noteweave.conversation;

import com.noteweave.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/workspaces/{workspaceId}/context-v2-rollout")
public class ContextV2RolloutController {
    private final ContextV2RolloutService service;
    private final ContextV2ShadowDiffService diffs;

    public ContextV2RolloutController(ContextV2RolloutService service,
                                      ContextV2ShadowDiffService diffs) {
        this.service = service;
        this.diffs = diffs;
    }

    @GetMapping
    ApiResponse<ContextV2RolloutService.RolloutState> get(@PathVariable String workspaceId) {
        return ApiResponse.success(service.get(workspaceId));
    }

    @PutMapping
    ApiResponse<ContextV2RolloutService.RolloutState> set(@PathVariable String workspaceId,
                                                            @Valid @RequestBody RolloutRequest request) {
        return ApiResponse.success(service.set(workspaceId, request.mode()));
    }

    public record RolloutRequest(@NotBlank String mode) {}

    @GetMapping("/runs/{answerRunId}/diff")
    ApiResponse<ContextV2ShadowDiffService.ShadowDiff> diff(@PathVariable String workspaceId,
                                                              @PathVariable String answerRunId) {
        return ApiResponse.success(diffs.get(workspaceId, answerRunId));
    }

    @GetMapping("/cohort")
    ApiResponse<ContextV2ShadowDiffService.ShadowCohort> cohort(
            @PathVariable String workspaceId, @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(diffs.recent(workspaceId, limit));
    }
}
