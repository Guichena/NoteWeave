package com.noteweave.research;

public record ResearchVerifierSummaryResponse(
        String localVerifierStatus,
        String localVerifierReason,
        String globalVerifierDecision,
        String globalVerifierReason,
        String finalLoopDecision,
        String finalLoopReason,
        String researchIntentAlignmentStatus,
        String researchIntentAlignmentReason,
        ResearchRecoveryTargetsResponse recoveryTargets,
        ResearchVerifierGatedSummaryResponse verifierGatedSummary
) {
}
