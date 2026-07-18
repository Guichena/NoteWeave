package com.noteweave.memory;

import org.springframework.stereotype.Component;

@Component
public class MemoryCandidatePolicy {

    public static final String VERSION = "memory-candidate-policy-v1";

    public String version() {
        return VERSION;
    }

    public double confidenceForSource(String sourceType) {
        return switch (sourceType) {
            case "USER_FEEDBACK" -> 0.98;
            case "PROJECT_DECISION" -> 0.97;
            case "ARTIFACT_FEEDBACK" -> 0.78;
            case "CONVERSATION_FEEDBACK" -> 0.72;
            case "MODEL_INFERENCE" -> 0.35;
            default -> 0.60;
        };
    }

    public double marginalUtility(
            String sourceType,
            String signalType,
            String taskNeighborhood
    ) {
        double score = switch (sourceType) {
            case "USER_FEEDBACK", "PROJECT_DECISION" -> 0.90;
            case "ARTIFACT_FEEDBACK", "CONVERSATION_FEEDBACK" -> 0.72;
            default -> 0.40;
        };
        if ("DECISION".equals(signalType) || "NEGATIVE".equals(signalType)) {
            score += 0.05;
        }
        if ("COMMON".equals(taskNeighborhood)) {
            score += 0.03;
        }
        return Math.min(score, 0.99);
    }

    public double riskScore(
            String sourceType,
            String signalType,
            String taskNeighborhood,
            String conflictStatus
    ) {
        double risk = switch (sourceType) {
            case "USER_FEEDBACK" -> 0.10;
            case "PROJECT_DECISION" -> 0.15;
            case "ARTIFACT_FEEDBACK" -> 0.25;
            case "CONVERSATION_FEEDBACK" -> 0.30;
            case "MODEL_INFERENCE" -> 0.65;
            default -> 0.45;
        };
        if ("NEGATIVE".equals(signalType)) {
            risk += 0.05;
        }
        if ("COMMON".equals(taskNeighborhood)) {
            risk += 0.05;
        }
        if ("CONFLICTING_ACTIVE_MEMORY".equals(conflictStatus)) {
            risk += 0.65;
        }
        return Math.min(risk, 0.99);
    }

    public double minimumEvidenceConfidence() {
        return 0.70;
    }

    public double minimumAutoPromotionUtility() {
        return 0.60;
    }

    public double reviewRequiredRisk() {
        return 0.70;
    }

    public double equivalentStatementSimilarity() {
        return 0.85;
    }
}
