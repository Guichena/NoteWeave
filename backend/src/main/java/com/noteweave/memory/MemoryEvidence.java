package com.noteweave.memory;

/**
 * Beta 后验和跨会话权重。
 * 创建记忆时写入先验；之后每个新会话按来源权重累加，同一会话只计一次。
 */
public final class MemoryEvidence {
    public enum Band {
        EXPLICIT,
        INFERRED,
        MODEL
    }

    public record Posterior(double alpha, double beta) {
        public double mean() {
            double total = alpha + beta;
            return total <= 0 ? 0 : alpha / total;
        }

        public double variance() {
            double total = alpha + beta;
            if (total <= 0) {
                return 1;
            }
            return (alpha * beta) / (total * total * (total + 1));
        }
    }

    private MemoryEvidence() {
    }

    /** implicitFeedback 为 true 时，对话反馈按推断而不是明确表达取先验。 */
    public static Band bandOf(String sourceType, boolean implicitFeedback) {
        if ("MODEL_INFERENCE".equals(sourceType)) {
            return Band.MODEL;
        }
        if (implicitFeedback || "ARTIFACT_FEEDBACK".equals(sourceType)) {
            return Band.INFERRED;
        }
        if ("USER_FEEDBACK".equals(sourceType)
                || "PROJECT_DECISION".equals(sourceType)
                || "CONVERSATION_FEEDBACK".equals(sourceType)) {
            return Band.EXPLICIT;
        }
        return Band.INFERRED;
    }

    public static Band band(String stored) {
        if (stored == null || stored.isBlank()) {
            return Band.INFERRED;
        }
        try {
            return Band.valueOf(stored.trim());
        } catch (IllegalArgumentException ex) {
            return Band.INFERRED;
        }
    }

    public static Posterior prior(Band band) {
        return switch (band) {
            case EXPLICIT -> new Posterior(4, 1);
            case INFERRED -> new Posterior(1.5, 1.5);
            case MODEL -> new Posterior(1, 2);
        };
    }

    public static double sourceWeight(Band band) {
        return switch (band) {
            case EXPLICIT -> 1.0;
            case INFERRED -> 0.5;
            case MODEL -> 0.25;
        };
    }

    /** recordedSessions 含创建时记下的会话。同一会话由调用方直接跳过，不进这个公式。 */
    public static double weight(Band band, int recordedSessions) {
        int recorded = Math.max(0, recordedSessions);
        return sourceWeight(band) / (1.0 + recorded);
    }
}
