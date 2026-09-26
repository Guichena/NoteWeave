package com.noteweave.infra;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

import com.noteweave.answer.AnswerLiveEvent;
import com.noteweave.answer.AnswerRealtimeBridgeUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.StreamOperations;

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

    @Test
    void conversationPublishRetriesOneTransientRedisFailure() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForStream()).thenReturn(streams);
        when(values.increment(anyString())).thenThrow(new RedisConnectionFailureException("reconnecting"))
                .thenReturn(1L);

        RedisAnswerRealtimeBridge bridge = bridge(redis);

        var result = bridge.publishConversation(
                "conversation-1",
                "run-1",
                new AnswerLiveEvent(1, "answer.delta", "value", Instant.now()));

        org.assertj.core.api.Assertions.assertThat(result.sequence()).isEqualTo(1L);
        org.mockito.Mockito.verify(values, org.mockito.Mockito.times(2)).increment(anyString());
    }

    private RedisAnswerRealtimeBridge bridge(StringRedisTemplate redis) {
        return new RedisAnswerRealtimeBridge(redis, new SimpleMeterRegistry(), 64, 60);
    }
}
