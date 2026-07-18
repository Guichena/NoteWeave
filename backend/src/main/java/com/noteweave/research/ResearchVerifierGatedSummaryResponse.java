package com.noteweave.research;

import java.util.List;
import java.util.Map;

public record ResearchVerifierGatedSummaryResponse(
        int blockedRowCount,
        int guardrailedRowCount,
        int recoveryTargetedBlockedRowCount,
        int uncoveredBlockedRowCount,
        int requirementPartialBlockedRowCount,
        List<Map<String, Object>> blockedRowSamples,
        List<Map<String, Object>> guardrailedRowSamples,
        List<Map<String, Object>> needMoreEvidenceRowSamples
) {
}
