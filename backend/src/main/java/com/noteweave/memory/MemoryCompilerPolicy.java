package com.noteweave.memory;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class MemoryCompilerPolicy {

    public static final String VERSION = "memory-compiler-policy-v1";

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

    public boolean scopeAllowed(String memoryScope, String ownerUserId, String currentUserId) {
        if ("USER".equals(memoryScope)) {
            return currentUserId != null && currentUserId.equals(ownerUserId);
        }
        return "WORKSPACE".equals(memoryScope);
    }

    public int scopePriority(String memoryScope) {
        return "USER".equals(memoryScope) ? 0 : 1;
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

    public Comparator<RankableMemory> comparator() {
        return Comparator.comparingInt(RankableMemory::scopePriority)
                .thenComparingInt(RankableMemory::neighborhoodPriority)
                .thenComparing(Comparator.comparingDouble(
                        RankableMemory::utilityScore).reversed())
                .thenComparing(RankableMemory::updatedAt, Comparator.reverseOrder())
                .thenComparing(RankableMemory::memoryObjectId);
    }

    /** 与上下文预算用同一套估算规则（见 ContextTokenEstimator），每条再加 1 个 token 作为提示词里的分隔开销。 */
    public int estimateTokens(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return com.noteweave.conversation.ContextTokenEstimator.estimate(value.strip()) + 1;
    }

    public record RankableMemory(
            String memoryObjectId,
            int scopePriority,
            int neighborhoodPriority,
            double utilityScore,
            Instant updatedAt
    ) {
    }
}
