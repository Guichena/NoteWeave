package com.noteweave.research;

import java.util.List;

public record ResearchCounterfactualBranchResponse(
        String branchId,
        String sessionId,
        String parentBranchId,
        String parentSessionId,
        String branchReason,
        String branchStatus,
        String executionMode,
        List<String> siblingBranchIds,
        String decision,
        String verifierScope,
        String hypothesisSummary,
        List<String> targetEvidenceIds
) {
}
