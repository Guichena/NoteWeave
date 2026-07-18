package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MemoryCompilerPolicyTest {

    private final MemoryCompilerPolicy policy = new MemoryCompilerPolicy();

    @Test
    void shouldPrioritizeScopeSpecificityUtilityAndFreshnessInOrder() {
        Instant now = Instant.now();
        List<MemoryCompilerPolicy.RankableMemory> rows = new java.util.ArrayList<>(List.of(
                row("common-high", 1, 2, 0.99, now),
                row("workspace-exact", 1, 0, 0.80, now.minusSeconds(60)),
                row("user-exact", 0, 0, 0.60, now.minusSeconds(120)),
                row("workspace-exact-fresh", 1, 0, 0.80, now)
        ));

        rows.sort(policy.comparator());

        assertThat(rows).extracting(MemoryCompilerPolicy.RankableMemory::memoryObjectId)
                .containsExactly(
                        "user-exact",
                        "workspace-exact-fresh",
                        "workspace-exact",
                        "common-high");
    }

    @Test
    void shouldApplyScopeAndNeighborhoodRules() {
        assertThat(policy.scopeAllowed("USER", "user-a", "user-a")).isTrue();
        assertThat(policy.scopeAllowed("USER", "user-a", "user-b")).isFalse();
        assertThat(policy.scopeAllowed("WORKSPACE", "user-a", "user-b")).isTrue();
        assertThat(policy.neighborhoodPriority(
                List.of("CHAT_QA"), "CHAT_QA", Set.of("COMMON", "CHAT", "CHAT_QA")))
                .isZero();
        assertThat(policy.neighborhoodPriority(
                List.of("CHAT"), "CHAT_QA", Set.of("COMMON", "CHAT", "CHAT_QA")))
                .isEqualTo(1);
        assertThat(policy.neighborhoodPriority(
                List.of("COMMON"), "CHAT_QA", Set.of("COMMON", "CHAT", "CHAT_QA")))
                .isEqualTo(2);
    }

    @Test
    void shouldExposeVersionedBudgetsAndDeterministicTokenEstimate() {
        assertThat(policy.version()).isEqualTo("memory-compiler-policy-v1");
        assertThat(policy.maximumTokens("chat")).isEqualTo(320);
        assertThat(policy.maximumTokens("artifact")).isEqualTo(480);
        assertThat(policy.estimateTokens("结论先行")).isEqualTo(5);
        assertThat(policy.estimateTokens("concise answer")).isGreaterThan(1);
    }

    private MemoryCompilerPolicy.RankableMemory row(
            String id,
            int scope,
            int neighborhood,
            double utility,
            Instant updatedAt
    ) {
        return new MemoryCompilerPolicy.RankableMemory(
                id, scope, neighborhood, utility, updatedAt);
    }
}
