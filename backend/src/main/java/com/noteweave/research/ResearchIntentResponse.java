package com.noteweave.research;

import java.util.List;

public record ResearchIntentResponse(
        String researchGoal,
        String deliverableFormat,
        List<String> constraints,
        String timeRange,
        String depth,
        String researchType
) {
}
