package com.noteweave.research;

public record ResearchSourceEvidenceSummaryResponse(
        String sourceBasis,
        String primaryQuality,
        String qualityMixLabel,
        String readStrategyMixLabel,
        String fetchFoundationLabel,
        String orchestrationFoundationLabel,
        int verifiedFindingCount,
        int citationCount
) {
}
