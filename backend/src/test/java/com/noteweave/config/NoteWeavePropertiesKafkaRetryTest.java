package com.noteweave.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Kafka 重试预算配置的绑定与边界约束测试，不依赖 Spring 上下文。
 */
class NoteWeavePropertiesKafkaRetryTest {

    @Test
    void defaultRetryBudgetIsFiveAttemptsWithThirtySecondCap() {
        NoteWeaveProperties.Kafka kafka = new NoteWeaveProperties.Kafka(true, null);
        assertThat(kafka.retryMaxAttempts()).isEqualTo(5);
        assertThat(kafka.retryMaxBackoffMs()).isEqualTo(30_000L);
    }

    @Test
    void retryAttemptsAreClampedIntoOneToTen() {
        assertThat(new NoteWeaveProperties.Kafka(true, null, 0, 30_000L).retryMaxAttempts()).isEqualTo(5);
        assertThat(new NoteWeaveProperties.Kafka(true, null, -3, 30_000L).retryMaxAttempts()).isEqualTo(5);
        assertThat(new NoteWeaveProperties.Kafka(true, null, 1, 30_000L).retryMaxAttempts()).isEqualTo(1);
        assertThat(new NoteWeaveProperties.Kafka(true, null, 10, 30_000L).retryMaxAttempts()).isEqualTo(10);
        assertThat(new NoteWeaveProperties.Kafka(true, null, 42, 30_000L).retryMaxAttempts()).isEqualTo(10);
    }

    @Test
    void nonPositiveBackoffCapFallsBackToDefault() {
        assertThat(new NoteWeaveProperties.Kafka(true, null, 5, 0L).retryMaxBackoffMs()).isEqualTo(30_000L);
        assertThat(new NoteWeaveProperties.Kafka(true, null, 5, -1L).retryMaxBackoffMs()).isEqualTo(30_000L);
        assertThat(new NoteWeaveProperties.Kafka(true, null, 5, 2_000L).retryMaxBackoffMs()).isEqualTo(2_000L);
    }

    @Test
    void rootPropertiesDefaultKafkaRetryBudget() {
        NoteWeaveProperties properties = new NoteWeaveProperties(null, null, null, null, null, null);
        assertThat(properties.kafka().retryMaxAttempts()).isEqualTo(5);
        assertThat(properties.kafka().retryMaxBackoffMs()).isEqualTo(30_000L);
    }

    @Test
    void kafkaTopicsStillDefaultWhenOnlyEnabledFlagProvided() {
        NoteWeaveProperties properties = new NoteWeaveProperties(null, null, null,
                new NoteWeaveProperties.Kafka(true, null), null, null);
        assertThat(properties.kafka().topics()).isNotNull();
        assertThat(properties.kafka().topics().sourceParse()).isNotBlank();
        assertThat(properties.kafka().retryMaxAttempts()).isEqualTo(5);
    }

    @Test
    void defaultedTopicsKeepConfiguredRetryBudget() {
        NoteWeaveProperties properties = new NoteWeaveProperties(null, null, null,
                new NoteWeaveProperties.Kafka(true, null, 7, 12_000L), null, null);
        assertThat(properties.kafka().topics()).isNotNull();
        assertThat(properties.kafka().retryMaxAttempts()).isEqualTo(7);
        assertThat(properties.kafka().retryMaxBackoffMs()).isEqualTo(12_000L);
    }
}
