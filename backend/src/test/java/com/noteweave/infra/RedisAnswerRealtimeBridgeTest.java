package com.noteweave.infra;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.noteweave.answer.AnswerLiveEvent;
import com.noteweave.answer.AnswerRealtimeBridgeUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisAnswerRealtimeBridgeTest {

    @Test
    void readFailureMustNotLookLikeAnEmptyEventBatch() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForStream()).thenThrow(new RedisConnectionFailureException("redis unavailable"));
        RedisAnswerRealtimeBridge bridge = bridge(redis);

        assertThatThrownBy(() -> bridge.readAfter("run-1", 0, Duration.ofMillis(10)))
                .isInstanceOf(AnswerRealtimeBridgeUnavailableException.class)
                .hasMessageContaining("answer read");
    }

    @Test
    void publishFailureMustExposeTheLocalMuxDegradationSignal() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForStream()).thenThrow(new RedisConnectionFailureException("redis unavailable"));
        RedisAnswerRealtimeBridge bridge = bridge(redis);

        assertThatThrownBy(() -> bridge.publish(
                "run-1", new AnswerLiveEvent(1, "answer.delta", "value", Instant.now())))
                .isInstanceOf(AnswerRealtimeBridgeUnavailableException.class)
                .hasMessageContaining("answer publish");
    }

    private RedisAnswerRealtimeBridge bridge(StringRedisTemplate redis) {
        return new RedisAnswerRealtimeBridge(redis, new SimpleMeterRegistry(), 64, 60);
    }
}
