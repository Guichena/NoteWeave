package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.answer.EvidenceSufficiency;
import com.noteweave.answer.strategy.AnswerContext;
import com.noteweave.answer.strategy.AnswerMode;
import com.noteweave.answer.strategy.AnswerStrategyContractValidator;
import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.PromptSpec;
import com.noteweave.answer.strategy.RetrievalPlan;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EvidenceSufficiencyPromptTest {
    private final AnswerStrategyContractValidator validator = new AnswerStrategyContractValidator();
    private final QaAnswerModeStrategy strategy = new QaAnswerModeStrategy();

    @Test
    void partialAppendsCoverageInstructionAndKeepsTheOriginalSentence() {
        EvidenceBundle bundle = bundle(evidence());
        PromptSpec original = strategy.compose(context(), bundle);

        PromptSpec adjusted = EvidenceSufficiencyPrompts.apply(
                original, EvidenceSufficiency.PARTIAL, bundle);

        assertThat(adjusted.refusal()).isFalse();
        assertThat(adjusted.userPrompt()).contains("资料只覆盖问题的一部分时，先回答能回答的部分");
        assertThat(adjusted.userPrompt()).contains(EvidenceSufficiencyPrompts.PARTIAL_INSTRUCTION);
        assertThat(adjusted.referencedEvidenceIds()).containsExactly("passage:alpha");
        assertThat(adjusted.promptVersion()).isEqualTo(original.promptVersion());
        validator.validatePromptAndPolicy(plan(), bundle, adjusted, strategy.policy());
    }

    @Test
    void insufficientBecomesRefusalWithoutEvidenceIds() {
        EvidenceBundle bundle = bundle(evidence());
        PromptSpec original = strategy.compose(context(), bundle);

        PromptSpec adjusted = EvidenceSufficiencyPrompts.apply(
                original, EvidenceSufficiency.INSUFFICIENT, bundle);

        assertThat(adjusted.refusal()).isTrue();
        assertThat(adjusted.referencedEvidenceIds()).isEmpty();
        assertThat(adjusted.userPrompt()).contains("资料标题").contains("Alpha 标题");
        assertThat(adjusted.userPrompt()).doesNotContain("passage:alpha");
        assertThat(adjusted.userPrompt()).doesNotContain("请直接回答");
        assertThat(adjusted.promptVersion()).isEqualTo(QaAnswerModeStrategy.PLAN_VERSION);
        validator.validatePromptAndPolicy(plan(), bundle, adjusted, strategy.policy());
    }

    @Test
    void sufficientNotRunAndExistingRefusalKeepTheOriginalPrompt() {
        EvidenceBundle bundle = bundle(evidence());
        PromptSpec original = strategy.compose(context(), bundle);
        assertThat(EvidenceSufficiencyPrompts.apply(original, EvidenceSufficiency.SUFFICIENT, bundle))
                .isSameAs(original);
        assertThat(EvidenceSufficiencyPrompts.apply(original, EvidenceSufficiency.NOT_RUN, bundle))
                .isSameAs(original);

        PromptSpec empty = strategy.compose(context(), bundle());
        assertThat(empty.refusal()).isTrue();
        assertThat(empty.userPrompt()).contains("当前工作台资料中暂未检索到足够依据");
        assertThat(EvidenceSufficiencyPrompts.apply(empty, EvidenceSufficiency.NOT_RUN, bundle()))
                .isSameAs(empty);
        assertThat(EvidenceSufficiencyPrompts.apply(empty, EvidenceSufficiency.INSUFFICIENT, bundle()))
                .isSameAs(empty);
    }

    private RetrievalPlan plan() {
        return new RetrievalPlan(
                QaAnswerModeStrategy.PLAN_VERSION,
                AnswerMode.QA,
                List.of(new RetrievalPlan.Step("QA_PASSAGE", 3, 1, Map.of("workspace_id", "workspace"))),
                new RetrievalPlan.Budget(3, 1000, 0, 0));
    }

    private AnswerContext context() {
        return new AnswerContext(
                "workspace", "conversation", "message", "问题",
                Set.of(), Map.of(), Instant.now());
    }

    private EvidenceBundle bundle(EvidenceBundle.Evidence... evidence) {
        return new EvidenceBundle(
                "bundle", QaAnswerModeStrategy.PLAN_VERSION, List.of(evidence),
                false, List.of(), Instant.now());
    }

    private EvidenceBundle.Evidence evidence() {
        return new EvidenceBundle.Evidence(
                "passage:alpha", "PASSAGE", "source", "snapshot", "passage", "", "",
                "Alpha 标题", "摘录", "location", 0.2, 0.8, 0.8,
                "scope", Instant.now(), "selected", 4, Map.of());
    }
}
