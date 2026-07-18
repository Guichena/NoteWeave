package com.noteweave.conversation;

import com.noteweave.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/workspaces/{workspaceId}/runs")
public class RunInputSnapshotController {

    private final RunInputSnapshotService runInputSnapshotService;

    public RunInputSnapshotController(RunInputSnapshotService runInputSnapshotService) {
        this.runInputSnapshotService = runInputSnapshotService;
    }

    @GetMapping("/{executionKind}/{runId}/input-snapshot")
    ApiResponse<RunInputSnapshotResponse> get(
            @PathVariable String workspaceId,
            @PathVariable String executionKind,
            @PathVariable String runId
    ) {
        return ApiResponse.success(runInputSnapshotService.get(workspaceId, executionKind, runId));
    }
}
