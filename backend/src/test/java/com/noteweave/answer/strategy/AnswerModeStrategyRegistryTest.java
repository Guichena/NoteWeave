package com.noteweave.answer.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnswerModeStrategyRegistryTest {

    @Test
    void shouldResolveOneStrategyPerMode() {
        AnswerModeStrategy qa = strategy(AnswerMode.QA);
        AnswerModeStrategy note = strategy(AnswerMode.NOTE);
        AnswerModeStrategy wiki = strategy(AnswerMode.WIKI);
        AnswerModeStrategyRegistry registry = new AnswerModeStrategyRegistry(List.of(qa, note, wiki));

        assertThat(registry.require("qa")).isSameAs(qa);
        assertThat(registry.require(AnswerMode.NOTE)).isSameAs(note);
        assertThat(registry.registeredStrategies()).hasSize(3);
    }

    @Test
    void shouldRejectDuplicateStrategiesAtStartup() {
        assertThatThrownBy(() -> new AnswerModeStrategyRegistry(List.of(
                strategy(AnswerMode.QA), strategy(AnswerMode.QA))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QA");
    }

    @Test
    void shouldRejectUnknownModeExplicitly() {
        AnswerModeStrategyRegistry registry = new AnswerModeStrategyRegistry(List.of(strategy(AnswerMode.QA)));

        assertThatThrownBy(() -> registry.require("research"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("ANSWER_MODE_UNSUPPORTED");
    }

    @Test
    void shouldRejectMissingRegisteredStrategy() {
        AnswerModeStrategyRegistry registry = new AnswerModeStrategyRegistry(List.of(strategy(AnswerMode.QA)));

        assertThatThrownBy(() -> registry.require(AnswerMode.WIKI))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("ANSWER_MODE_STRATEGY_MISSING");
    }

    private AnswerModeStrategy strategy(AnswerMode mode) {
        return new AnswerModeStrategy() {
            @Override
            public AnswerMode supports() {
                return mode;
            }

            @Override
            public RetrievalPlan plan(AnswerContext context) {
                return new RetrievalPlan("test-v1", mode, List.of(), null);
            }

            @Override
            public PromptSpec compose(AnswerContext context, EvidenceBundle evidenceBundle) {
                return new PromptSpec("system", "user", List.of(), "test-v1");
            }

            @Override
            public AnswerPolicy policy() {
                return new AnswerPolicy(0, true, false, 100);
            }
        };
    }
}
