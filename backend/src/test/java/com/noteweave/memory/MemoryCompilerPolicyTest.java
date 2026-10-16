package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MemoryCompilerPolicyTest {

    private final MemoryCompilerPolicy policy = new MemoryCompilerPolicy();

    @Test
    void shouldClassifyTerminologyAsOnDemandAndKeepGlobalStructureAlwaysOn() {
        MemorySignalService.MemoryCompileHints structure = hints(
                List.of(), List.of("以后回答先给结论再展开"), List.of());
        MemorySignalService.MemoryCompileHints terminologyOnly = hints(
                List.of(), List.of(), List.of("保留页码"));
        MemorySignalService.MemoryCompileHints style = hints(
                List.of("回答保持简洁"), List.of(), List.of());

        assertThat(policy.memoryClass("terminology.term", style)).isEqualTo(MemoryCompilerPolicy.ON_DEMAND);
        assertThat(policy.memoryClass("format.structure", structure)).isEqualTo(MemoryCompilerPolicy.ALWAYS_ON);
        assertThat(policy.memoryClass(null, terminologyOnly)).isEqualTo(MemoryCompilerPolicy.ON_DEMAND);
        assertThat(policy.memoryClass(null, style)).isEqualTo(MemoryCompilerPolicy.ALWAYS_ON);
        assertThat(policy.selectedReason(MemoryCompilerPolicy.ALWAYS_ON))
                .isEqualTo("适用于全部回答的常驻约束");
        assertThat(policy.selectedReason(MemoryCompilerPolicy.ON_DEMAND))
                .isEqualTo("与问题相关度较高，按价值和篇幅选入");
    }

    @Test
    void shouldMultiplyConfidenceUtilityRelevanceAndRecency() {
        Instant now = Instant.parse("2026-10-12T00:00:00Z");
        assertThat(policy.confidence(4, 1)).isCloseTo(0.8, within(0.0001));
        assertThat(policy.confidence(0, 0)).isEqualTo(0.5);
        assertThat(policy.recency(now, now)).isEqualTo(1);
        assertThat(policy.recency(now.plus(1, ChronoUnit.DAYS), now)).isEqualTo(1);
        assertThat(policy.recency(now.minus(30, ChronoUnit.DAYS), now)).isCloseTo(0.5, within(0.0001));
        assertThat(policy.value(0.8, 0.5, 1, 0.5)).isCloseTo(0.2, within(0.0001));
        assertThat(policy.scopePriority("USER")).isZero();
        assertThat(policy.scopePriority("WORKSPACE")).isEqualTo(1);
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
        assertThat(policy.neighborhoodPriority(
                List.of("RESEARCH"), "CHAT_QA", Set.of("COMMON", "CHAT", "CHAT_QA")))
                .isEqualTo(3);
    }

    @Test
    void shouldExposeVersionedBudgetsAndDeterministicTokenEstimate() {
        assertThat(policy.version()).isEqualTo("memory-compiler-policy-v2");
        assertThat(policy.maximumTokens("chat")).isEqualTo(320);
        assertThat(policy.maximumTokens("artifact")).isEqualTo(480);
        assertThat(policy.estimateTokens("结论先行")).isEqualTo(5);
        assertThat(policy.estimateTokens("concise answer")).isGreaterThan(1);
        assertThat(policy.memoryTokens(
                hints(List.of(), List.of(), List.of()), "common")).isEqualTo(3);
    }

    private MemorySignalService.MemoryCompileHints hints(
            List<String> style,
            List<String> structure,
            List<String> terminology
    ) {
        return new MemorySignalService.MemoryCompileHints(
                style, structure, terminology, List.of(), List.of(), List.of());
    }
}
