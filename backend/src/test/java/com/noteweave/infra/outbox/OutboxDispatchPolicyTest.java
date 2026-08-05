package com.noteweave.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OutboxDispatchPolicyTest {

    @Test
    void retryDelayUsesBoundedPositiveJitter() {
        for (int attempt = 0; attempt <= 6; attempt++) {
            long base = Math.min(60L, 1L << Math.min(attempt, 6));
            long maxJitter = Math.max(1L, base / 5L);
            for (int sample = 0; sample < 50; sample++) {
                long delay = OutboxDispatchPolicy.retryDelaySeconds(attempt);
                assertThat(delay).isBetween(base, Math.min(60L, base + maxJitter));
            }
        }
    }

    @Test
    void retryDelayNeverExceedsSixtySeconds() {
        assertThat(OutboxDispatchPolicy.retryDelaySeconds(100)).isEqualTo(60L);
    }
}
