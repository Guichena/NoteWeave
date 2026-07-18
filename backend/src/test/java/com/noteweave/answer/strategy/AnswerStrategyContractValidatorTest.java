package com.noteweave.answer.strategy;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AnswerStrategyContractValidatorTest {

    private final AnswerStrategyContractValidator validator =
            new AnswerStrategyContractValidator();

    @Test
    void shouldAcceptEvidenceBackedAnswerAndExplicitRefusal() {
        AnswerContext context = context(Set.of());
        RetrievalPlan plan = plan(AnswerMode.QA, Map.of("workspace_id", "workspace"));
        EvidenceBundle evidenceBundle = bundle(List.of(evidence("passage:one")));
        PromptSpec answer = new PromptSpec(
                "system", "answer", List.of("passage:one"), "plan-v1");
        PromptSpec refusal = new PromptSpec(
                "system", "insufficient evidence", List.of(), "plan-v1", true);
        AnswerPolicy policy = new AnswerPolicy(1, false, true, 1000);

        assertThatCode(() -> validator.validatePlan(AnswerMode.QA, context, plan))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validatePromptAndPolicy(
                plan, evidenceBundle, answer, policy)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validatePromptAndPolicy(
                plan, bundle(List.of()), refusal, policy)).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectModeWorkspaceOrSourceScopeMismatchInPlan() {
        AnswerContext scopedContext = context(Set.of("source-b", "source-a"));

        assertPlanInvalid(AnswerMode.QA, scopedContext,
                plan(AnswerMode.NOTE, Map.of(
                        "workspace_id", "workspace", "source_ids", "source-a,source-b")));
        assertPlanInvalid(AnswerMode.QA, scopedContext,
                plan(AnswerMode.QA, Map.of(
                        "workspace_id", "other", "source_ids", "source-a,source-b")));
        assertPlanInvalid(AnswerMode.QA, scopedContext,
                plan(AnswerMode.QA, Map.of(
                        "workspace_id", "workspace", "source_ids", "source-a")));
    }

    @Test
    void shouldRejectInvalidStepOrBudget() {
        RetrievalPlan invalidStep = new RetrievalPlan(
                "plan-v1", AnswerMode.QA,
                List.of(new RetrievalPlan.Step(
                        "QA_PASSAGE", 0, Double.NaN, Map.of("workspace_id", "workspace"))),
                new RetrievalPlan.Budget(1, 100, 0, 0));
        RetrievalPlan invalidBudget = new RetrievalPlan(
                "plan-v1", AnswerMode.QA,
                List.of(new RetrievalPlan.Step(
                        "QA_PASSAGE", 1, 1, Map.of("workspace_id", "workspace"))),
                new RetrievalPlan.Budget(0, -1, -1, -1, -1, -1));

        assertPlanInvalid(AnswerMode.QA, context(Set.of()), invalidStep);
        assertPlanInvalid(AnswerMode.QA, context(Set.of()), invalidBudget);
    }

    @Test
    void shouldRejectNonRefusalWithoutRequiredEvidenceOrCitation() {
        RetrievalPlan plan = plan(AnswerMode.QA, Map.of("workspace_id", "workspace"));
        AnswerPolicy policy = new AnswerPolicy(1, false, true, 1000);

        assertPromptPolicyInvalid(plan, bundle(List.of()),
                new PromptSpec("system", "answer", List.of(), "plan-v1"), policy);
        assertPromptPolicyInvalid(plan, bundle(List.of(evidence("passage:one"))),
                new PromptSpec("system", "answer", List.of(), "plan-v1"), policy);
    }

    @Test
    void shouldRejectPromptVersionOutsideEvidenceOrRefusalCitation() {
        RetrievalPlan plan = plan(AnswerMode.QA, Map.of("workspace_id", "workspace"));
        EvidenceBundle bundle = bundle(List.of(evidence("passage:one")));
        AnswerPolicy policy = new AnswerPolicy(1, false, true, 1000);

        assertPromptPolicyInvalid(plan, bundle,
                new PromptSpec("system", "answer", List.of("passage:one"), "other-v1"), policy);
        assertPromptPolicyInvalid(plan, bundle,
                new PromptSpec("system", "answer", List.of("passage:missing"), "plan-v1"), policy);
        assertPromptPolicyInvalid(plan, bundle,
                new PromptSpec("system", "refusal", List.of("passage:one"), "plan-v1", true), policy);
    }

    private AnswerContext context(Set<String> sourceScope) {
        return new AnswerContext(
                "workspace", "conversation", "message", "query",
                sourceScope, Map.of(), Instant.now());
    }

    private RetrievalPlan plan(AnswerMode mode, Map<String, String> filters) {
        return new RetrievalPlan(
                "plan-v1", mode,
                List.of(new RetrievalPlan.Step("QA_PASSAGE", 3, 1, filters)),
                new RetrievalPlan.Budget(3, 1000, 0, 0));
    }

    private EvidenceBundle bundle(List<EvidenceBundle.Evidence> evidence) {
        return new EvidenceBundle(
                "bundle", "plan-v1", evidence, false, List.of(), Instant.now());
    }

    private EvidenceBundle.Evidence evidence(String evidenceId) {
        return new EvidenceBundle.Evidence(
                evidenceId, "PASSAGE", "source", "snapshot", "chunk",
                "", "", "title", "excerpt", "chunk:0",
                1, 1, 1, "workspace-source:source", Instant.now(),
                "selected", 7, Map.of());
    }

    private void assertPlanInvalid(
            AnswerMode expectedMode,
            AnswerContext context,
            RetrievalPlan plan
    ) {
        assertThatThrownBy(() -> validator.validatePlan(expectedMode, context, plan))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("ANSWER_RETRIEVAL_PLAN_INVALID");
    }

    private void assertPromptPolicyInvalid(
            RetrievalPlan plan,
            EvidenceBundle bundle,
            PromptSpec prompt,
            AnswerPolicy policy
    ) {
        assertThatThrownBy(() -> validator.validatePromptAndPolicy(
                plan, bundle, prompt, policy))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("ANSWER_PROMPT_POLICY_INVALID");
    }
}
