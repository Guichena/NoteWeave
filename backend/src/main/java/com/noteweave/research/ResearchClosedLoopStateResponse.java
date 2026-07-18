package com.noteweave.research;

import java.util.List;
import java.util.Map;

public record ResearchClosedLoopStateResponse(
        String activeBranchId,
        String localVerifierStatus,
        String globalVerifierDecision,
        String finalLoopDecision,
        int loopRoundsCount,
        int ledgerRowCount,
        int branchCount,
        int verifierDecisionCount,
        Map<String, Object> harnessSummary,
        Map<String, Object> checkpointCandidate,
        Map<String, Object> harnessControlState,
        Map<String, Object> auditSummaries,
        Map<String, Object> toolboxSummary,
        ResearchCounterfactualSummaryResponse counterfactualSummary,
        ResearchRecoveryTargetsResponse recoveryTargets,
        ResearchStateLedgerResponse stateLedger,
        Map<String, Object> localVerifier,
        Map<String, Object> globalVerifier,
        List<Map<String, Object>> branches,
        List<Map<String, Object>> rows,
        List<Map<String, Object>> cells,
        List<Map<String, Object>> verifierDecisions,
        List<ResearchCheckpointSummaryResponse> checkpoints,
        List<Map<String, Object>> sourceEvidence,
        List<Map<String, Object>> cellEvidence,
        List<Map<String, Object>> branchDecisions,
        List<Map<String, Object>> loopRounds,
        Map<String, Object> loopDecisionPayload
) {
}
