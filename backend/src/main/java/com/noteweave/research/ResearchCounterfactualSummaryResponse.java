package com.noteweave.research;

import java.util.List;

public record ResearchCounterfactualSummaryResponse(
        boolean hasCounterfactualRecheck,
        int counterfactualBranchCount,
        int conflictedRowCount,
        String localVerifierStatus,
        String globalVerifierDecision,
        String recoveryMode,
        List<String> counterfactualBranchIds,
        List<String> counterfactualSessionIds,
        List<String> activeCounterfactualBranchIds,
        List<String> activeCounterfactualSessionIds,
        List<String> branchReasons,
        List<String> targetEvidenceIds,
        List<ResearchCounterfactualBranchResponse> branches
) {
}
