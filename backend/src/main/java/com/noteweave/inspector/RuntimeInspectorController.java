package com.noteweave.inspector;

import com.fasterxml.jackson.databind.JsonNode;
import com.noteweave.common.ApiResponse;
import com.noteweave.conversation.ContextInspectorResponse;
import com.noteweave.conversation.RunInputSnapshotResponse;
import com.noteweave.conversation.RunInputSnapshotService;
import com.noteweave.conversation.RunReplayResponse;
import com.noteweave.memory.CanonicalMemoryReviewService;
import com.noteweave.memory.MemoryInspectorResponse;
import com.noteweave.memory.MemoryRuntime;
import com.noteweave.memory.MemoryRuntimeQuery;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/workspaces/{workspaceId}")
public class RuntimeInspectorController {
    private final RunInputSnapshotService snapshotService;
    private final MemoryRuntime memoryRuntime;
    private final CanonicalMemoryReviewService reviewService;
    private final WorkspaceAccessGuard workspaceAccessGuard;

    public RuntimeInspectorController(
            RunInputSnapshotService snapshotService,
            MemoryRuntime memoryRuntime,
            CanonicalMemoryReviewService reviewService,
            WorkspaceAccessGuard workspaceAccessGuard
    ) {
        this.snapshotService = snapshotService;
        this.memoryRuntime = memoryRuntime;
        this.reviewService = reviewService;
        this.workspaceAccessGuard = workspaceAccessGuard;
    }

    @GetMapping("/context-inspector/runs/{executionKind}/{runId}")
    ApiResponse<ContextInspectorResponse> context(
            @PathVariable String workspaceId,
            @PathVariable String executionKind,
            @PathVariable String runId
    ) {
        RunInputSnapshotResponse snapshot = snapshotService.get(workspaceId, executionKind, runId);
        JsonNode selected = snapshot.snapshot();
        Map<String, JsonNode> identities = new LinkedHashMap<>();
        for (String field : new String[]{"segment_summary_refs", "recent_message_refs", "memory_revision_refs"}) {
            identities.put(field, selected.path(field));
        }
        return ApiResponse.success(new ContextInspectorResponse(
                snapshot.executionKind(), snapshot.runId(), snapshot.replayAvailability(),
                snapshot.retrievalConfig(), snapshot.snapshot(), identities));
    }

    @GetMapping("/run-replay/{executionKind}/{runId}")
    ApiResponse<RunReplayResponse> replay(
            @PathVariable String workspaceId,
            @PathVariable String executionKind,
            @PathVariable String runId
    ) {
        RunInputSnapshotResponse snapshot = snapshotService.get(workspaceId, executionKind, runId);
        return ApiResponse.success(new RunReplayResponse(
                snapshot.executionKind(), snapshot.runId(), snapshot.replayAvailability(),
                "FULL".equals(snapshot.replayAvailability()), snapshot.snapshot()));
    }

    @GetMapping("/memory/inspector")
    ApiResponse<MemoryInspectorResponse> memory(@PathVariable String workspaceId) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.WORKSPACE_READ);
        return ApiResponse.success(new MemoryInspectorResponse(
                memoryRuntime.recall(new MemoryRuntimeQuery(workspaceId)),
                reviewService.list(workspaceId)));
    }
}
