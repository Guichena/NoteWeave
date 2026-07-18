package com.noteweave.research;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResearchCheckpointStateLedgerSummaryResponse(
        String activeBranchId,
        int rowCount,
        int columnCount,
        int branchCount,
        int cellCount,
        int verifiedRowCount,
        int conflictedRowCount,
        int requirementReadyRowCount,
        int requirementPartialRowCount,
        int readWindowCount,
        int evidenceCardCount,
        List<Map<String, Object>> verifiedRowSamples,
        List<Map<String, Object>> conflictedRowSamples,
        List<Map<String, Object>> evidenceCardSamples,
        List<Map<String, Object>> readWindowSamples,
        int blockedRowCount,
        int guardrailedRowCount,
        int recoveryTargetedBlockedRowCount,
        int uncoveredBlockedRowCount,
        int requirementPartialBlockedRowCount,
        List<Map<String, Object>> blockedRowSamples,
        List<Map<String, Object>> guardrailedRowSamples,
        List<Map<String, Object>> needMoreEvidenceRowSamples,
        Map<String, Object> intentCompletionContract,
        int intentRequirementCount,
        int intentSatisfiedRequirementCount,
        int intentPendingRequirementCount,
        List<String> missingIntentRequirements
) {
}
