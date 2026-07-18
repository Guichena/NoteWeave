package com.noteweave.memory;

import com.noteweave.common.BusinessException;
import org.springframework.stereotype.Component;

@Component
public class MemoryOutcomePolicy {

    public static final String VERSION = "memory-outcome-policy-v1";

    public String version() {
        return VERSION;
    }

    public Decision evaluate(State state, String outcomeType, Double editMagnitude) {
        String normalizedOutcome = normalizeOutcome(outcomeType);
        double score = outcomeScore(normalizedOutcome, editMagnitude);
        int applicationCount = state.applicationCount() + 1;
        int positiveCount = state.positiveOutcomeCount()
                + (isPositive(normalizedOutcome) ? 1 : 0);
        int negativeCount = state.negativeOutcomeCount()
                + ("NEGATIVE".equals(normalizedOutcome) ? 1 : 0);
        int editCount = state.editOutcomeCount()
                + ("EDITED".equals(normalizedOutcome) ? 1 : 0);
        int retryCount = state.retryOutcomeCount()
                + ("RETRIED".equals(normalizedOutcome) ? 1 : 0);
        double utility = round(clamp(state.utilityScore() * 0.75 + score * 0.25));
        int adverseCount = negativeCount + editCount + retryCount;
        double adverseRatio = applicationCount == 0
                ? 0.0 : adverseCount / (double) applicationCount;
        boolean stale = applicationCount >= 3
                && (utility <= 0.40 || adverseRatio >= 0.67);
        boolean reviewRequired = stale
                || "NEGATIVE".equals(normalizedOutcome)
                || adverseCount >= 2;
        String objectStatus = switch (state.objectStatus()) {
            case "REVOKED", "CONFLICTED" -> state.objectStatus();
            default -> stale ? "STALE" : state.objectStatus();
        };
        return new Decision(
                VERSION,
                normalizedOutcome,
                score,
                utility,
                applicationCount,
                positiveCount,
                negativeCount,
                editCount,
                retryCount,
                objectStatus,
                reviewRequired ? "REVIEW_REQUIRED" : state.reviewStatus()
        );
    }

    private String normalizeOutcome(String outcomeType) {
        String value = MemorySignalService.normalizeToken(outcomeType);
        return switch (value) {
            case "ACCEPTED", "POSITIVE", "EDITED", "RETRIED", "NEGATIVE" -> value;
            default -> throw new BusinessException(
                    "MEMORY_OUTCOME_TYPE_INVALID",
                    "不支持的 Memory outcome type: " + value
            );
        };
    }

    private double outcomeScore(String outcomeType, Double editMagnitude) {
        return switch (outcomeType) {
            case "ACCEPTED", "POSITIVE" -> 1.0;
            case "EDITED" -> Math.max(0.20, 1.0 - 0.80 * magnitude(editMagnitude));
            case "RETRIED" -> 0.15;
            case "NEGATIVE" -> 0.0;
            default -> throw new IllegalStateException("Unsupported outcome " + outcomeType);
        };
    }

    private boolean isPositive(String outcomeType) {
        return "ACCEPTED".equals(outcomeType) || "POSITIVE".equals(outcomeType);
    }

    private double magnitude(Double editMagnitude) {
        return editMagnitude == null ? 1.0 : clamp(editMagnitude);
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    public record State(
            double utilityScore,
            int applicationCount,
            int positiveOutcomeCount,
            int negativeOutcomeCount,
            int editOutcomeCount,
            int retryOutcomeCount,
            String objectStatus,
            String reviewStatus
    ) {
    }

    public record Decision(
            String policyVersion,
            String outcomeType,
            double outcomeScore,
            double utilityScore,
            int applicationCount,
            int positiveOutcomeCount,
            int negativeOutcomeCount,
            int editOutcomeCount,
            int retryOutcomeCount,
            String objectStatus,
            String reviewStatus
    ) {
    }
}
