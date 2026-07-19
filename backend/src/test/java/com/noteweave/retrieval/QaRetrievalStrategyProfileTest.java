package com.noteweave.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.answer.strategy.AnswerMode;
import com.noteweave.answer.strategy.RetrievalPlan;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QaRetrievalStrategyProfileTest {

    @Test
    void shouldExposeTheSingleStableV2Tuple() {
        assertThat(QaRetrievalStrategyProfile.V2)
                .returns("qa-weknora-hybrid-v1", QaRetrievalStrategyProfile::profileVersion)
                .returns("qa-weknora-hybrid-v1", QaRetrievalStrategyProfile::planVersion)
                .returns(QaEvidenceRelevancePolicy.POLICY_VERSION_V2,
                        QaRetrievalStrategyProfile::relevancePolicyVersion)
                .returns(true, QaRetrievalStrategyProfile::v2Enabled);
    }

    @Test
    void shouldResolveOnlyTheExactV2PlanTuple() {
        RetrievalPlan v2 = plan(QaRetrievalStrategyProfile.V2, Map.of());

        assertThat(QaRetrievalStrategyProfile.fromPlan(v2, v2.steps().get(0)))
                .isEqualTo(QaRetrievalStrategyProfile.V2);
    }

    @Test
    void shouldFailClosedWhenPlanLabelAndPolicyTupleDrift() {
        RetrievalPlan drifted = plan(
                QaRetrievalStrategyProfile.V2,
                Map.of(QaRetrievalStrategyProfile.FILTER_RELEVANCE_POLICY,
                        QaEvidenceRelevancePolicy.POLICY_VERSION_V1));

        assertThatThrownBy(() -> QaRetrievalStrategyProfile.fromPlan(
                drifted, drifted.steps().get(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inconsistent QA retrieval strategy tuple");
    }

    private RetrievalPlan plan(
            QaRetrievalStrategyProfile profile,
            Map<String, String> overrides
    ) {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("workspace_id", "workspace");
        filters.put("snapshot_status", "ACTIVE");
        filters.put(QaRetrievalStrategyProfile.FILTER_PROFILE, profile.profileVersion());
        filters.put(QaRetrievalStrategyProfile.FILTER_RELEVANCE_POLICY,
                profile.relevancePolicyVersion());
        filters.put(QaRetrievalStrategyProfile.FILTER_SELECTION_POLICY,
                profile.selectionPolicyVersion());
        filters.put(QaRetrievalStrategyProfile.FILTER_V2_ENABLED,
                Boolean.toString(profile.v2Enabled()));
        filters.putAll(overrides);
        RetrievalPlan.Step step = new RetrievalPlan.Step(
                QaRetrievalStrategyProfile.CHANNEL,
                QaRetrievalStrategyProfile.CANDIDATE_LIMIT,
                QaRetrievalStrategyProfile.STEP_WEIGHT,
                filters);
        return new RetrievalPlan(
                profile.planVersion(),
                AnswerMode.QA,
                List.of(step),
                new RetrievalPlan.Budget(
                        profile.maxEvidence(), profile.maxEvidenceCharacters(), 0, 0));
    }
}
