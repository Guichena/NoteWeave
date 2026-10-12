package com.noteweave.memory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MemoryCandidatePolicy {

    public static final String VERSION = "memory-candidate-policy-v2";

    private final double minimumEvidenceConfidence;
    private final double minimumAutoPromotionUtility;
    private final double reviewRequiredRisk;
    private final double maximumPosteriorVariance;

    public MemoryCandidatePolicy() {
        this(0.70, 0.60, 0.70, 0.04);
    }

    @Autowired
    public MemoryCandidatePolicy(
            @Value("${noteweave.memory.policy.minimum-evidence-confidence:0.70}") double minimumEvidenceConfidence,
            @Value("${noteweave.memory.policy.minimum-auto-promotion-utility:0.60}") double minimumAutoPromotionUtility,
            @Value("${noteweave.memory.policy.review-required-risk:0.70}") double reviewRequiredRisk,
            @Value("${noteweave.memory.policy.maximum-posterior-variance:0.04}") double maximumPosteriorVariance
    ) {
        this.minimumEvidenceConfidence = minimumEvidenceConfidence;
        this.minimumAutoPromotionUtility = minimumAutoPromotionUtility;
        this.reviewRequiredRisk = reviewRequiredRisk;
        this.maximumPosteriorVariance = maximumPosteriorVariance;
    }

    public String version() {
        return VERSION;
    }

    /** 门控用的证据分是先验均值。隐式反馈由抽取侧按 INFERRED 写入，不走这个来源默认值。 */
    public double confidenceForSource(String sourceType) {
        return MemoryEvidence.prior(MemoryEvidence.bandOf(sourceType, false)).mean();
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

    public double riskScore(String category, String scope, String conflictStatus) {
        double risk = switch (category == null ? "" : category) {
            case "forbidden" -> 0.28;
            case "terminology" -> 0.18;
            case "structure" -> 0.14;
            case "interaction" -> 0.12;
            default -> 0.10;
        };
        risk += "WORKSPACE".equals(scope) ? 0.16 : 0.06;
        if ("CONFLICTING_ACTIVE_MEMORY".equals(conflictStatus)
                || "CONFLICT_CHECK_NOT_RUN".equals(conflictStatus)) {
            risk += 0.55;
        }
        return Math.min(risk, 0.99);
    }

    public double minimumEvidenceConfidence() {
        return minimumEvidenceConfidence;
    }

    public double minimumAutoPromotionUtility() {
        return minimumAutoPromotionUtility;
    }

    public double reviewRequiredRisk() {
        return reviewRequiredRisk;
    }

    public double maximumPosteriorVariance() {
        return maximumPosteriorVariance;
    }

    public double equivalentStatementSimilarity() {
        return 0.85;
    }
}
