package com.noteweave.research;

import java.util.List;

public record ResearchIntentAlignmentResponse(
        String status,
        String reasonCode,
        String goalStatus,
        String deliverableStatus,
        String timeRangeStatus,
        String depthStatus,
        int satisfiedConstraintCount,
        int totalConstraintCount,
        List<String> coveredRequirements,
        List<String> missingRequirements
) {
}
