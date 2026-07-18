package com.noteweave.research;

import java.util.List;

public record ResearchLoopRoundSummaryResponse(
        int roundNo,
        int searchHitCount,
        int readWindowCount,
        int evidenceCardCount,
        List<String> searchQueries,
        List<String> evidenceIds,
        String branchDecision,
        String globalDecision
) {
}
