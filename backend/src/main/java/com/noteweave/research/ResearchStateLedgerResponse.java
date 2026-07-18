package com.noteweave.research;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResearchStateLedgerResponse(
        String activeBranchId,
        List<Map<String, Object>> columns,
        List<Map<String, Object>> branchSessions,
        List<Map<String, Object>> branches,
        List<Map<String, Object>> rows,
        List<Map<String, Object>> cells,
        List<Map<String, Object>> verifierDecisions,
        List<Map<String, Object>> requiredFindingContract,
        List<Map<String, Object>> requiredFindingProgress,
        int verifiedRowCount,
        int conflictedRowCount,
        int requirementReadyRowCount,
        int requirementPartialRowCount,
        ResearchRecoveryTargetsResponse recoveryTargets,
        Map<String, Object> intentCompletionContract
) {
}
