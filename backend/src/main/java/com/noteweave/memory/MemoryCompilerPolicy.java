package com.noteweave.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MemoryCompilerPolicy {

    public static final String VERSION = "memory-compiler-policy-v2";
    public static final String ALWAYS_ON = "ALWAYS_ON";
    public static final String ON_DEMAND = "ON_DEMAND";
    public static final String REASON_ALWAYS_ON = "适用于全部回答的常驻约束";
    public static final String REASON_ON_DEMAND = "与问题相关度较高，按价值和篇幅选入";
    public static final String REASON_IRRELEVANT = "与当前问题无关";
    public static final String REASON_BUDGET = "超出 token 预算";
    public static final int RRF_K = 60;

    private final double recencyHalfLifeDays;

    public MemoryCompilerPolicy() {
        this(30);
    }

    @Autowired
    public MemoryCompilerPolicy(
            @Value("${noteweave.memory.compiler.recency-half-life-days:30}") double recencyHalfLifeDays
    ) {
        this.recencyHalfLifeDays = recencyHalfLifeDays > 0 ? recencyHalfLifeDays : 30;
    }

    public String version() {
        return VERSION;
    }

    public int maximumTokens(String packType) {
        return switch (packType) {
            case "chat" -> 320;
            case "artifact", "research" -> 480;
            default -> 320;
        };
    }

    /** 只写入引用字段。选择顺序看价值密度，不再按作用域把用户记忆排到前面。 */
    public int scopePriority(String memoryScope) {
        return "USER".equals(memoryScope) ? 0 : 1;
    }

    public boolean scopeAllowed(String memoryScope, String ownerUserId, String currentUserId) {
        if ("USER".equals(memoryScope)) {
            return currentUserId != null && currentUserId.equals(ownerUserId);
        }
        return "WORKSPACE".equals(memoryScope);
    }

    public int neighborhoodPriority(
            List<String> neighborhoods,
            String exactNeighborhood,
            Set<String> allowedNeighborhoods
    ) {
        if (neighborhoods.contains(exactNeighborhood)) {
            return 0;
        }
        for (String neighborhood : neighborhoods) {
            if (!"COMMON".equals(neighborhood) && allowedNeighborhoods.contains(neighborhood)) {
                return 1;
            }
        }
        return neighborhoods.contains("COMMON") ? 2 : 3;
    }

    /**
     * terminology 是按需类。结构、风格、禁止和交互先作常驻，
     * 因为当前槽位分不出全局写法和某个主题专用的结构。
     */
    public String memoryClass(String slotKey, MemorySignalService.MemoryCompileHints hints) {
        String slot = slotKey == null ? "" : slotKey.toLowerCase(java.util.Locale.ROOT);
        if (slot.startsWith("terminology.")) {
            return ON_DEMAND;
        }
        if (hints != null && !hints.terminologyPolicy().isEmpty()
                && hints.styleConstraints().isEmpty()
                && hints.structureConstraints().isEmpty()
                && hints.forbiddenPatterns().isEmpty()
                && hints.interactionPolicy().isEmpty()
                && hints.reviewChecklist().isEmpty()) {
            return ON_DEMAND;
        }
        return ALWAYS_ON;
    }

    public double confidence(double alpha, double beta) {
        double safeAlpha = Math.max(0, alpha);
        double safeBeta = Math.max(0, beta);
        if (safeAlpha + safeBeta <= 0) {
            return 0.5;
        }
        return safeAlpha / (safeAlpha + safeBeta);
    }

    /** 距最近一次强化的指数衰减。半衰期内降到一半，刚记录过的记忆取 1。 */
    public double recency(Instant basis, Instant now) {
        if (basis == null || now == null || !basis.isBefore(now)) {
            return 1;
        }
        double days = Duration.between(basis, now).toMillis() / 86_400_000d;
        return Math.pow(0.5d, days / recencyHalfLifeDays);
    }

    public double value(double confidence, double utility, double relevance, double recency) {
        return Math.max(0, confidence) * Math.max(0, utility)
                * clampUnit(relevance) * clampUnit(recency);
    }

    public int memoryTokens(MemorySignalService.MemoryCompileHints hints, String displayText) {
        int tokens = 0;
        if (hints != null) {
            tokens += tokenSum(hints.styleConstraints());
            tokens += tokenSum(hints.structureConstraints());
            tokens += tokenSum(hints.terminologyPolicy());
            tokens += tokenSum(hints.forbiddenPatterns());
            tokens += tokenSum(hints.interactionPolicy());
            tokens += tokenSum(hints.reviewChecklist());
        }
        if (tokens == 0) {
            tokens = estimateTokens(displayText);
        }
        return Math.max(tokens, 1);
    }

    public String selectedReason(String memoryClass) {
        return ON_DEMAND.equals(memoryClass) ? REASON_ON_DEMAND : REASON_ALWAYS_ON;
    }

    /** 与上下文预算用同一套估算规则（见 ContextTokenEstimator），每条再加 1 个 token 作为提示词里的分隔开销。 */
    public int estimateTokens(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return com.noteweave.conversation.ContextTokenEstimator.estimate(value.strip()) + 1;
    }

    private int tokenSum(List<String> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        return values.stream().mapToInt(this::estimateTokens).sum();
    }

    private double clampUnit(double value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, 1);
    }
}
