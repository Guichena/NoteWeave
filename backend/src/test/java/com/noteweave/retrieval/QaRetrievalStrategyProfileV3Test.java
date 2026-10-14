package com.noteweave.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.answer.strategy.AnswerContext;
import com.noteweave.answer.strategy.RetrievalPlan;
import com.noteweave.chat.QaAnswerModeStrategy;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 新计划解析为 V3；历史 V2 计划仍能按原参数解析，预算被改动的计划会被拒绝。 */
class QaRetrievalStrategyProfileV3Test {

    @Test
    void newPlansResolveToV3WithTheTokenBudgetRecorded() {
        RetrievalPlan plan = newPlan();

        assertThat(QaRetrievalStrategyProfile.fromPlan(plan, plan.steps().get(0)))
                .isEqualTo(QaRetrievalStrategyProfile.V3);
        assertThat(plan.steps().get(0).filters())
                .containsEntry(QaRetrievalStrategyProfile.FILTER_EVIDENCE_TOKEN_BUDGET, "8000");
        assertThat(plan.budget().maxEvidence()).isEqualTo(12);
    }

    @Test
    void persistedV2PlansStillResolveToV2() {
        RetrievalPlan current = newPlan();
        Map<String, String> filters = new LinkedHashMap<>(current.steps().get(0).filters());
        filters.put(QaRetrievalStrategyProfile.FILTER_PROFILE, "qa-weknora-hybrid-v1");
        filters.put(QaRetrievalStrategyProfile.FILTER_SELECTION_POLICY, "qa-source-diverse-budget-v2");
        filters.remove(QaRetrievalStrategyProfile.FILTER_EVIDENCE_TOKEN_BUDGET);
        RetrievalPlan.Step step = new RetrievalPlan.Step(
                current.steps().get(0).channel(), current.steps().get(0).candidateLimit(),
                current.steps().get(0).weight(), filters);
        RetrievalPlan legacy = new RetrievalPlan(current.version(), current.mode(),
                java.util.List.of(step), new RetrievalPlan.Budget(6, 8_000, 0, 0));

        assertThat(QaRetrievalStrategyProfile.fromPlan(legacy, step))
                .isEqualTo(QaRetrievalStrategyProfile.V2);
    }

    @Test
    void v3PlanWithAChangedTokenBudgetIsRejected() {
        RetrievalPlan current = newPlan();
        Map<String, String> filters = new LinkedHashMap<>(current.steps().get(0).filters());
        filters.put(QaRetrievalStrategyProfile.FILTER_EVIDENCE_TOKEN_BUDGET, "4000");
        RetrievalPlan.Step step = new RetrievalPlan.Step(
                current.steps().get(0).channel(), current.steps().get(0).candidateLimit(),
                current.steps().get(0).weight(), filters);
        RetrievalPlan tampered = new RetrievalPlan(current.version(), current.mode(),
                java.util.List.of(step), current.budget());

        assertThatThrownBy(() -> QaRetrievalStrategyProfile.fromPlan(tampered, step))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private RetrievalPlan newPlan() {
        return new QaAnswerModeStrategy().plan(new AnswerContext(
                "workspace", "conversation", "message", "query", Set.of(), Map.of(), Instant.now()));
    }
}
