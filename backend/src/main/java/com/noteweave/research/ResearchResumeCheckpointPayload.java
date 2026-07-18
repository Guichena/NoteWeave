package com.noteweave.research;

import java.util.Map;

public record ResearchResumeCheckpointPayload(
        String sourceResearchRunId,
        int checkpointNo,
        String snapshotType,
        String activeBranchId,
        String finalLoopDecision,
        Map<String, Object> payload
) {
}
