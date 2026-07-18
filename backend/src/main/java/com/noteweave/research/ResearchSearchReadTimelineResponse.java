package com.noteweave.research;

import java.util.List;

public record ResearchSearchReadTimelineResponse(
        int loopRoundCount,
        int totalSearchHitCount,
        int totalReadWindowCount,
        int totalEvidenceCardCount,
        List<String> allSearchQueries,
        String finalLoopDecision,
        String finalLoopReason,
        String terminalDisposition,
        boolean handoffRequired,
        String abandonReason,
        List<ResearchLoopRoundSummaryResponse> rounds
) {
}
