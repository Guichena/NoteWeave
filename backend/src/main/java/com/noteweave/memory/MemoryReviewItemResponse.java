package com.noteweave.memory;

import java.time.Instant;
import java.util.List;

public record MemoryReviewItemResponse(
        String reviewKind,
        String reviewId,
        String workspaceId,
        String statement,
        List<String> taskNeighborhoods,
        String reviewStatus,
        String lifecycleStatus,
        String conflictStatus,
        String evidenceGateStatus,
        double riskScore,
        double utilityScore,
        String policyVersion,
        String latestVersionId,
        int priority,
        Instant createdAt,
        Instant updatedAt
) {
}
