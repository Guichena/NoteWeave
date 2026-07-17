package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ResearchAgentRolloutPolicyTest {

    private final ResearchAgentRolloutPolicy policy = new ResearchAgentRolloutPolicy(
            10, 0.20d, 0.20d, 0.30d);

    @Test
    void shouldContinueDuringColdStartAndPauseWhenAnyDurableFailureRateExceedsThreshold() {
        var coldStart = policy.evaluate(new ResearchAgentRolloutPolicy.HealthSnapshot(9, 9, 9, 9, 9));
        var leaseFailure = policy.evaluate(new ResearchAgentRolloutPolicy.HealthSnapshot(20, 5, 0, 0, 20));
        var deliveryFailure = policy.evaluate(new ResearchAgentRolloutPolicy.HealthSnapshot(20, 0, 5, 0, 20));
        var rejectedMerge = policy.evaluate(new ResearchAgentRolloutPolicy.HealthSnapshot(20, 0, 0, 7, 13));

        assertThat(coldStart.action()).isEqualTo(ResearchAgentRolloutPolicy.Action.CONTINUE);
        assertThat(coldStart.reasonCodes()).containsExactly("INSUFFICIENT_SAMPLE");
        assertThat(leaseFailure.action()).isEqualTo(ResearchAgentRolloutPolicy.Action.PAUSE_NEW_INITIAL_WAVES);
        assertThat(leaseFailure.reasonCodes()).containsExactly("TERMINAL_FAILURE_RATE_EXCEEDED");
        assertThat(deliveryFailure.reasonCodes()).containsExactly("DELIVERY_FAILURE_RATE_EXCEEDED");
        assertThat(rejectedMerge.reasonCodes()).containsExactly("REJECTED_MERGE_RATE_EXCEEDED");
    }
}
