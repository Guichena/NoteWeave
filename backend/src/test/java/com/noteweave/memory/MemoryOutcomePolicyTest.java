package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import org.junit.jupiter.api.Test;

class MemoryOutcomePolicyTest {

    private final MemoryOutcomePolicy policy = new MemoryOutcomePolicy();

    @Test
    void shouldIncreaseUtilityForAcceptedOutcome() {
        MemoryOutcomePolicy.Decision decision = policy.evaluate(
                state(0.70, 0, 0, 0, 0, 0),
                "ACCEPTED",
                null);

        assertThat(decision.policyVersion()).isEqualTo("memory-outcome-policy-v1");
        assertThat(decision.utilityScore()).isGreaterThan(0.70);
        assertThat(decision.positiveOutcomeCount()).isEqualTo(1);
        assertThat(decision.reviewStatus()).isEqualTo("APPROVED");
        assertThat(decision.objectStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void shouldLowerUtilityAndRequireReviewForNegativeOutcome() {
        MemoryOutcomePolicy.Decision decision = policy.evaluate(
                state(0.90, 0, 0, 0, 0, 0),
                "NEGATIVE",
                null);

        assertThat(decision.utilityScore()).isLessThan(0.90);
        assertThat(decision.negativeOutcomeCount()).isEqualTo(1);
        assertThat(decision.reviewStatus()).isEqualTo("REVIEW_REQUIRED");
        assertThat(decision.objectStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void shouldMarkRepeatedAdverseOutcomesAsStale() {
        MemoryOutcomePolicy.State state = state(0.90, 0, 0, 0, 0, 0);
        MemoryOutcomePolicy.Decision first = policy.evaluate(state, "RETRIED", null);
        MemoryOutcomePolicy.Decision second = policy.evaluate(toState(first), "EDITED", 1.0);
        MemoryOutcomePolicy.Decision third = policy.evaluate(toState(second), "NEGATIVE", null);

        assertThat(third.applicationCount()).isEqualTo(3);
        assertThat(third.objectStatus()).isEqualTo("STALE");
        assertThat(third.reviewStatus()).isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    void shouldRejectUnknownOutcomeType() {
        assertThatThrownBy(() -> policy.evaluate(
                state(0.50, 0, 0, 0, 0, 0), "IGNORED", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Memory outcome type");
    }

    private MemoryOutcomePolicy.State state(
            double utility,
            int applications,
            int positive,
            int negative,
            int edited,
            int retried
    ) {
        return new MemoryOutcomePolicy.State(
                utility,
                applications,
                positive,
                negative,
                edited,
                retried,
                "ACTIVE",
                "APPROVED"
        );
    }

    private MemoryOutcomePolicy.State toState(MemoryOutcomePolicy.Decision decision) {
        return new MemoryOutcomePolicy.State(
                decision.utilityScore(),
                decision.applicationCount(),
                decision.positiveOutcomeCount(),
                decision.negativeOutcomeCount(),
                decision.editOutcomeCount(),
                decision.retryOutcomeCount(),
                decision.objectStatus(),
                decision.reviewStatus()
        );
    }
}
