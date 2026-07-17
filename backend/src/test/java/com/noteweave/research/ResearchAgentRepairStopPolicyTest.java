package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ResearchAgentRepairStopPolicyTest {

    @Test
    void shouldPreferCancellationDeadlineAndBudgetStopsOverRepair() {
        var gap = gap("entity-1:claim", true, false, 0);
        assertThat(ResearchAgentRepairStopPolicy.evaluate(new ResearchAgentRepairStopPolicy.Snapshot(
                true, false, true, 2, List.of(gap))).kind())
                .isEqualTo(ResearchAgentRepairStopPolicy.DecisionKind.STOPPED_CANCELLED);
        assertThat(ResearchAgentRepairStopPolicy.evaluate(new ResearchAgentRepairStopPolicy.Snapshot(
                false, true, true, 2, List.of(gap))).kind())
                .isEqualTo(ResearchAgentRepairStopPolicy.DecisionKind.STOPPED_DEADLINE);
        assertThat(ResearchAgentRepairStopPolicy.evaluate(new ResearchAgentRepairStopPolicy.Snapshot(
                false, false, false, 2, List.of(gap))).kind())
                .isEqualTo(ResearchAgentRepairStopPolicy.DecisionKind.STOPPED_BUDGET_EXHAUSTED);
    }

    @Test
    void shouldCreateStableCounterfactualTargetsWithIndependentSourceExclusion() {
        var decision = ResearchAgentRepairStopPolicy.evaluate(new ResearchAgentRepairStopPolicy.Snapshot(
                false, false, true, 2, List.of(
                        gap("entity-2:claim", true, false, 0),
                        gap("entity-1:claim", true, false, 1))));

        assertThat(decision.kind()).isEqualTo(ResearchAgentRepairStopPolicy.DecisionKind.COUNTERFACTUAL);
        assertThat(decision.targets()).extracting(ResearchAgentRepairStopPolicy.RepairTarget::cellKey)
                .containsExactly("entity-1:claim", "entity-2:claim");
        assertThat(decision.targets().get(0).excludedSourceIds()).containsExactly("source-a", "source-b");
    }

    @Test
    void shouldNeverTargetFrozenForeignOrRepairCappedCells() {
        var decision = ResearchAgentRepairStopPolicy.evaluate(new ResearchAgentRepairStopPolicy.Snapshot(
                false, false, true, 1, List.of(
                        gap("entity-1:frozen", true, true, 0),
                        new ResearchAgentRepairStopPolicy.Gap("foreign:claim", true, false, false, 0, "sha256:foreign", Set.of("source-a")),
                        gap("entity-2:capped", true, false, 1))));

        assertThat(decision.kind()).isEqualTo(ResearchAgentRepairStopPolicy.DecisionKind.STOPPED_PARTIAL);
        assertThat(decision.targets()).isEmpty();
    }

    private ResearchAgentRepairStopPolicy.Gap gap(String cellKey, boolean required, boolean frozen, int repairCount) {
        return new ResearchAgentRepairStopPolicy.Gap(cellKey, required, true, frozen, repairCount,
                "sha256:" + cellKey, Set.of("source-b", "source-a"));
    }
}
