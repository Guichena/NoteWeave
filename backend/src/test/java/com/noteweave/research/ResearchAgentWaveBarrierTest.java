package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResearchAgentWaveBarrierTest {

    @Test
    void shouldBlockWaveWhileAnyTaskIsStillActive() {
        var result = ResearchAgentWaveBarrier.evaluate(List.of(
                new ResearchAgentWaveBarrier.TaskOutcome("task-b", "SUBMITTED", true),
                new ResearchAgentWaveBarrier.TaskOutcome("task-a", "RUNNING", false)));

        assertThat(result.state()).isEqualTo(ResearchAgentWaveBarrier.BarrierState.BLOCKED_ACTIVE);
        assertThat(result.blockingTaskIds()).containsExactly("task-a");
    }

    @Test
    void shouldBlockExpiredTaskUntilItsRecoveryDecisionIsDurable() {
        var result = ResearchAgentWaveBarrier.evaluate(List.of(
                new ResearchAgentWaveBarrier.TaskOutcome("task-a", "EXPIRED", false)));

        assertThat(result.state()).isEqualTo(ResearchAgentWaveBarrier.BarrierState.BLOCKED_RECOVERY);
        assertThat(result.blockingTaskIds()).containsExactly("task-a");
    }

    @Test
    void shouldOpenOnlyForExplainableTerminalTaskSet() {
        var result = ResearchAgentWaveBarrier.evaluate(List.of(
                new ResearchAgentWaveBarrier.TaskOutcome("task-c", "CANCELLED", true),
                new ResearchAgentWaveBarrier.TaskOutcome("task-a", "SUBMITTED", true),
                new ResearchAgentWaveBarrier.TaskOutcome("task-b", "EXPIRED", true)));

        assertThat(result.state()).isEqualTo(ResearchAgentWaveBarrier.BarrierState.READY);
        assertThat(result.blockingTaskIds()).isEmpty();
    }

    @Test
    void shouldCanonicalizeDecisionIndependentlyOfInputMapOrder() {
        var first = ResearchAgentAdvanceDecisionCanonicalizer.canonicalize(new ResearchAgentAdvanceDecisionCanonicalizer.DecisionInput(
                "NEXT_WAVE", 2, 5, List.of("cell-b", "cell-a"), Map.of("budget", "ok", "reason", "gap")));
        var second = ResearchAgentAdvanceDecisionCanonicalizer.canonicalize(new ResearchAgentAdvanceDecisionCanonicalizer.DecisionInput(
                "NEXT_WAVE", 2, 5, List.of("cell-a", "cell-b"), Map.of("reason", "gap", "budget", "ok")));

        assertThat(first.canonicalJson()).isEqualTo(second.canonicalJson());
        assertThat(first.digest()).isEqualTo(second.digest());
    }
}
