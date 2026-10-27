package com.noteweave.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.config.NoteWeaveProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

/**
 * 消费重试退避策略的纯单元测试：不启动 Spring 上下文，直接构造 KafkaConfig 并注入属性。
 * <p>
 * 退避实现允许抖动（延迟落在 {@code [base, base + base/5]} 内），因此这里只断言
 * "基准值单调不减 + 每次延迟落在基准的抖动区间内"，不对相邻延迟做严格递增比较；
 * 需要精确结果时通过注入固定种子的 {@link Random} 保证确定性。
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

    /**
     * cap 生效后基准值恒等于 cap，抖动会让相邻延迟出现小幅回落（如 2058 → 2056），
     * 因此断言基准值单调不减、且每次延迟都落在该基准的抖动区间与 {@code cap * 1.2} 之内。
     */
    @Test
    void backoffCapBoundsEveryDelayAndKeepsBaseSequenceMonotonic() {
        long capMillis = 2_000L;
        NoteWeaveProperties.Kafka kafka = new NoteWeaveProperties.Kafka(true, null, 6, capMillis);
        List<Long> delays = collectDelays(configWith(kafka).exponentialJitterBackOff(new Random(0L)), 20);
        assertThat(delays).hasSize(6);
        assertDelaysWithinJitterOfMonotonicBases(delays, capMillis);
    }

    @Test
    void backoffCapBoundsEveryDelayAcrossManySeeds() {
        long capMillis = 2_000L;
        NoteWeaveProperties.Kafka kafka = new NoteWeaveProperties.Kafka(true, null, 6, capMillis);
        KafkaConfig config = configWith(kafka);
        for (long seed = 0L; seed < 200L; seed++) {
            List<Long> delays = collectDelays(config.exponentialJitterBackOff(new Random(seed)), 20);
            assertDelaysWithinJitterOfMonotonicBases(delays, capMillis);
        }
    }

    @Test
    void fixedSeedProducesReproducibleJitterSequence() {
        long capMillis = 2_000L;
        NoteWeaveProperties.Kafka kafka = new NoteWeaveProperties.Kafka(true, null, 6, capMillis);
        KafkaConfig config = configWith(kafka);
        List<Long> first = collectDelays(config.exponentialJitterBackOff(new Random(20240607L)), 20);
        List<Long> second = collectDelays(config.exponentialJitterBackOff(new Random(20240607L)), 20);
        assertThat(first).hasSize(6).containsExactlyElementsOf(second);
        assertDelaysWithinJitterOfMonotonicBases(first, capMillis);
    }

    @Test
    void jitterBoundsAreInclusiveOnBothEnds() {
        long capMillis = 2_000L;
        NoteWeaveProperties.Kafka kafka = new NoteWeaveProperties.Kafka(true, null, 6, capMillis);
        KafkaConfig config = configWith(kafka);

        List<Long> lowest = collectDelays(config.exponentialJitterBackOff(zeroJitterRandom()), 20);
        assertThat(lowest).containsExactly(1_000L, 2_000L, 2_000L, 2_000L, 2_000L, 2_000L);

        List<Long> highest = collectDelays(config.exponentialJitterBackOff(maxJitterRandom()), 20);
        assertThat(highest).containsExactly(1_200L, 2_400L, 2_400L, 2_400L, 2_400L, 2_400L);
    }

    @Test
    void missingPropertiesFallBackToDefaults() {
        KafkaConfig config = new KafkaConfig();
        assertThat(config.retryMaxAttempts()).isEqualTo(NoteWeaveProperties.Kafka.DEFAULT_RETRY_MAX_ATTEMPTS);
        assertThat(config.retryMaxBackoffMs()).isEqualTo(NoteWeaveProperties.Kafka.DEFAULT_RETRY_MAX_BACKOFF_MS);
        assertThat(collectDelays(config, 10)).hasSize(NoteWeaveProperties.Kafka.DEFAULT_RETRY_MAX_ATTEMPTS);
    }

    @Test
    void parserRetryAfterDrivesTheBackOffInterval() {
        BackOff backOff = configWith(null).retryBackOffFor(
                com.noteweave.source.SourceParserUnavailableException.rateLimited(java.time.Duration.ofSeconds(7)));
        assertThat(collectDelays(backOff, 10)).containsOnly(7_000L);
    }

    @Test
    void parserRetryAfterIsCappedByTheConfiguredMaximumBackOff() {
        NoteWeaveProperties.Kafka kafka = new NoteWeaveProperties.Kafka(true, null, 3, 2_000L);
        BackOff backOff = configWith(kafka).retryBackOffFor(
                com.noteweave.source.SourceParserUnavailableException.rateLimited(java.time.Duration.ofMinutes(5)));
        assertThat(collectDelays(backOff, 10)).containsOnly(2_000L);
    }

    @Test
    void parserFailuresWithoutRetryAfterKeepTheExponentialPolicy() {
        BackOff backOff = configWith(null).retryBackOffFor(
                com.noteweave.source.SourceParserUnavailableException.rejected(415));
        List<Long> delays = collectDelays(backOff, 10);
        assertThat(delays).hasSize(NoteWeaveProperties.Kafka.DEFAULT_RETRY_MAX_ATTEMPTS);
        assertThat(delays.get(0)).isBetween(1_000L, 1_200L);
    }

    /** 断言第 index 次延迟落在 {@code [base, base + max(1, base/5)]}，base = min(1000 << index, cap)。 */
    private static void assertDelaysWithinJitterOfMonotonicBases(List<Long> delays, long capMillis) {
        assertThat(delays).hasSize(6);
        long previousBase = 0L;
        for (int index = 0; index < delays.size(); index++) {
            long base = Math.min(1_000L << index, capMillis);
            long maxJitter = Math.max(1L, base / 5L);
            assertThat(base)
                    .as("base at index %d must be non-decreasing", index)
                    .isGreaterThanOrEqualTo(previousBase);
            previousBase = base;
            assertThat(delays.get(index))
                    .as("delay at index %d", index)
                    .isBetween(base, base + maxJitter)
                    .isLessThanOrEqualTo(Math.round(capMillis * 1.2d));
        }
    }

    private static List<Long> collectDelays(KafkaConfig config, int calls) {
        return collectDelays(config.exponentialJitterBackOff(), calls);
    }

    private static List<Long> collectDelays(BackOff backOff, int calls) {
        BackOffExecution execution = backOff.start();
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

    private static Random zeroJitterRandom() {
        return new Random() {
            @Override
            public long nextLong(long bound) {
                return 0L;
            }
        };
    }

    private static Random maxJitterRandom() {
        return new Random() {
            @Override
            public long nextLong(long bound) {
                return bound - 1L;
            }
        };
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
