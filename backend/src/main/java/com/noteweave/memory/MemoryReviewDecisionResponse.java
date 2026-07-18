package com.noteweave.memory;

import java.util.List;

public record MemoryReviewDecisionResponse(
        String reviewDecisionId,
        String reviewKind,
        String reviewId,
        String decision,
        String reviewStatus,
        String lifecycleStatus,
        MemoryObjectResponse promotedMemoryObject,
        List<String> revokedMemoryObjectIds
) {
}
