package com.noteweave.memory;

import org.springframework.stereotype.Component;

@Component
public class MemoryCandidateGate {

    private final MemoryCandidatePolicy policy;

    public MemoryCandidateGate(MemoryCandidatePolicy policy) {
        this.policy = policy;
    }

    public GateDecision evaluate(
            MemorySignalService.SignalRow signal,
            double marginalUtility,
            String conflictStatus
    ) {
        String scopeStatus = validScope(signal) ? "VALID" : "INVALID";
        String evidenceGateStatus = signal.confidenceScore()
                >= policy.minimumEvidenceConfidence() ? "PASS" : "NEEDS_REVIEW";
        double riskScore = policy.riskScore(
                signal.sourceType(),
                signal.signalType(),
                signal.taskNeighborhood(),
                conflictStatus);
        boolean ready = "PASS".equals(evidenceGateStatus)
                && marginalUtility >= policy.minimumAutoPromotionUtility()
                && riskScore < policy.reviewRequiredRisk()
                && "VALID".equals(scopeStatus)
                && !"CONFLICTING_ACTIVE_MEMORY".equals(conflictStatus);
        return new GateDecision(
                policy.version(),
                evidenceGateStatus,
                riskScore,
                scopeStatus,
                ready ? "READY" : "NEEDS_REVIEW");
    }

    private boolean validScope(MemorySignalService.SignalRow signal) {
        return signal.workspaceId() != null && !signal.workspaceId().isBlank()
                && signal.userId() != null && !signal.userId().isBlank()
                && signal.taskNeighborhood() != null
                && !signal.taskNeighborhood().isBlank();
    }

    public record GateDecision(
            String policyVersion,
            String evidenceGateStatus,
            double riskScore,
            String scopeStatus,
            String reviewStatus
    ) {
    }
}
