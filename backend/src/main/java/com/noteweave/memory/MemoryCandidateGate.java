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
        return evaluate(signal, marginalUtility, conflictStatus,
                MemorySlots.categoryOf(signal.signalText()), "USER");
    }

    public GateDecision evaluate(
            MemorySignalService.SignalRow signal,
            double marginalUtility,
            String conflictStatus,
            String category,
            String scope
    ) {
        String scopeStatus = validScope(signal) ? "VALID" : "INVALID";
        MemoryEvidence.Posterior prior = MemoryEvidence.prior(
                MemoryEvidence.bandOf(signal.sourceType(), false));
        boolean evidencePass = signal.confidenceScore() >= policy.minimumEvidenceConfidence()
                && prior.variance() <= policy.maximumPosteriorVariance();
        String evidenceGateStatus = evidencePass ? "PASS" : "NEEDS_REVIEW";
        double riskScore = policy.riskScore(category, scope, conflictStatus);
        boolean ready = "PASS".equals(evidenceGateStatus)
                && marginalUtility >= policy.minimumAutoPromotionUtility()
                && riskScore < policy.reviewRequiredRisk()
                && "VALID".equals(scopeStatus)
                && !blocking(conflictStatus);
        return new GateDecision(
                policy.version(),
                evidenceGateStatus,
                riskScore,
                scopeStatus,
                ready ? "READY" : "NEEDS_REVIEW");
    }

    private boolean blocking(String conflictStatus) {
        return "CONFLICTING_ACTIVE_MEMORY".equals(conflictStatus)
                || "CONFLICT_CHECK_NOT_RUN".equals(conflictStatus);
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
