package com.noteweave.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.config.NoteWeaveProperties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.backoff.BackOffExecution;

/**
 * 消费重试退避策略的纯单元测试：不启动 Spring 上下文，直接构造 KafkaConfig 并注入属性。
 */
class KafkaRetryBackOffTest {

    @Test
    void defaultBackOffStopsAfterFiveAttempts() {
        List<Long> delays = collectDelays(configWith(null), NoteWeaveProperties.Kafka.DEFAULT_RETRY_MAX_ATTEMPTS + 2);
        assertThat(delays).hasSize(NoteWeaveProperties.Kafka.DEFAULT_RETRY_MAX_ATTEMPTS);
        assertThat(delays).allSatisfy(delay -> assertThat(delay)
                .isNotEqualTo(BackOffExecution.STOP)
                .isPositive());
    }

    @Test
    void defaultBackOffIsExponentialMonotonicWithinJitter() {
        List<Long> delays = collectDelays(configWith(null), NoteWeaveProperties.Kafka.DEFAULT_RETRY_MAX_ATTEMPTS);
        assertThat(delays).hasSize(5);
        long previous = 0L;
        for (int index = 0; index < delays.size(); index++) {
            long base = 1_000L << index;
            long delay = delays.get(index);
            assertThat(delay).isBetween(base, base + Math.max(1L, base / 5L));
            assertThat(delay).isGreaterThan(previous);
            previous = delay;
        }
    }

    @Test
    void configuredAttemptsBoundTheRetryCount() {
        NoteWeaveProperties.Kafka kafka = new NoteWeaveProperties.Kafka(
                true, null, 3, NoteWeaveProperties.Kafka.DEFAULT_RETRY_MAX_BACKOFF_MS);
        KafkaConfig config = configWith(kafka);
        assertThat(config.retryMaxAttempts()).isEqualTo(3);
        assertThat(collectDelays(config, 10)).hasSize(3);
    }

    @Test
    void singleAttemptStopsImmediatelyAfterOneBackOff() {
        NoteWeaveProperties.Kafka kafka = new NoteWeaveProperties.Kafka(true, null, 1, 30_000L);
        assertThat(collectDelays(configWith(kafka), 5)).hasSize(1);
    }

    @Test
    void backoffCapBoundsEveryDelayAndKeepsTheSequenceMonotonic() {
        NoteWeaveProperties.Kafka kafka = new NoteWeaveProperties.Kafka(true, null, 6, 2_000L);
        KafkaConfig config = configWith(kafka);
        List<Long> delays = collectDelays(config, 20);
        assertThat(delays).hasSize(6);
        long previous = 0L;
        for (long delay : delays) {
            assertThat(delay).isBetween(1L, 2_000L + Math.max(1L, 2_000L / 5L));
            assertThat(delay).isGreaterThanOrEqualTo(previous);
            previous = delay;
        }
        assertThat(delays).allSatisfy(delay -> assertThat(delay).isLessThanOrEqualTo(2_400L));
    }

    @Test
    void missingPropertiesFallBackToDefaults() {
        KafkaConfig config = new KafkaConfig();
        assertThat(config.retryMaxAttempts()).isEqualTo(NoteWeaveProperties.Kafka.DEFAULT_RETRY_MAX_ATTEMPTS);
        assertThat(config.retryMaxBackoffMs()).isEqualTo(NoteWeaveProperties.Kafka.DEFAULT_RETRY_MAX_BACKOFF_MS);
        assertThat(collectDelays(config, 10)).hasSize(NoteWeaveProperties.Kafka.DEFAULT_RETRY_MAX_ATTEMPTS);
    }

    private static List<Long> collectDelays(KafkaConfig config, int calls) {
        BackOffExecution execution = config.exponentialJitterBackOff().start();
        List<Long> delays = new ArrayList<>();
        for (int index = 0; index < calls; index++) {
            long delay = execution.nextBackOff();
            if (delay == BackOffExecution.STOP) {
                break;
            }
            delays.add(delay);
        }
        return delays;
    }

    private static KafkaConfig configWith(NoteWeaveProperties.Kafka kafka) {
        KafkaConfig config = new KafkaConfig();
        if (kafka != null) {
            NoteWeaveProperties properties = new NoteWeaveProperties(null, null, null, kafka, null, null);
            ReflectionTestUtils.setField(config, "pipelineProperties", properties);
        }
        return config;
    }
}
