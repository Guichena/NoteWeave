package com.noteweave.research;

import java.time.Instant;

record ResearchCheckpointRecord(
        int checkpointNo,
        String snapshotType,
        String objectKey,
        String payloadSha256,
        long contentSize,
        String activeBranchKey,
        String finalLoopDecision,
        String summaryJson,
        Instant createdAt
) {
}
