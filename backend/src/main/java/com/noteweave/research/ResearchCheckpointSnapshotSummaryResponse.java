package com.noteweave.research;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResearchCheckpointSnapshotSummaryResponse(
        int checkpointNo,
        String snapshotType,
        Map<String, Object> loopDecision,
        Map<String, Object> localVerifier,
        Map<String, Object> globalVerifier,
        ResearchCheckpointStateLedgerSummaryResponse stateLedger,
        Map<String, Object> researchIntentAlignment,
        String researchIntentAlignmentStatus,
        String researchIntentAlignmentReason,
        int intentConstraintCount,
        int intentSatisfiedConstraintCount,
        Map<String, Object> intentCompletionContract,
        int intentRequirementCount,
        int intentSatisfiedRequirementCount,
        int intentPendingRequirementCount,
        List<String> missingIntentRequirements,
        ResearchCounterfactualSummaryResponse counterfactualSummary,
        ResearchRecoveryTargetsResponse recoveryTargets,
        ResearchVerifierGatedSummaryResponse verifierGatedSummary
) {
}
