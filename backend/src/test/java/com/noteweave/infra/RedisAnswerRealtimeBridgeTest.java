package com.noteweave.infra;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.noteweave.answer.AnswerLiveEvent;
import com.noteweave.answer.AnswerRealtimeBridgeUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import java.util.List;

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
        RedisConnectionFailureException failure = new RedisConnectionFailureException("redis unavailable");
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(Object[].class)))
                .thenThrow(failure);
        RedisAnswerRealtimeBridge bridge = bridge(redis);

        assertThatThrownBy(() -> bridge.publish(
                "run-1", new AnswerLiveEvent(1, "answer.delta", "value", Instant.now())))
                .isInstanceOf(AnswerRealtimeBridgeUnavailableException.class)
                .hasMessageContaining("atomic publish")
                .hasCause(failure);
        // run 发布不重试，必须把真正的 Redis 故障暴露给本地 mux 降级路径。
        verify(redis, times(1)).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(Object[].class));
    }

    @Test
    void conversationPublishRetriesOneTransientRedisFailure() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("reconnecting"))
                .thenReturn(1L);

        RedisAnswerRealtimeBridge bridge = bridge(redis);

        var result = bridge.publishConversation(
                "conversation-1",
                "run-1",
                new AnswerLiveEvent(1, "answer.delta", "value", Instant.now()));

        org.assertj.core.api.Assertions.assertThat(result.sequence()).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(result.runId()).isEqualTo("run-1");
        org.assertj.core.api.Assertions.assertThat(result.data()).isEqualTo("value");
        // 同一原子 Lua 发布恰好调用两次：首次瞬时故障，唯一一次重试成功。
        verify(redis, times(2)).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                eq(List.of("noteweave:v1:revoked:answer:run-1", "noteweave:v1:stream:conversation:conversation-1",
                        "noteweave:v1:seq:conversation:conversation-1", "noteweave:v1:stream:conversation:conversation-1:trimmed",
                        "noteweave:v1:stream:conversation:conversation-1:highwater")), any(Object[].class));
    }

    private RedisAnswerRealtimeBridge bridge(StringRedisTemplate redis) {
        return new RedisAnswerRealtimeBridge(redis, new SimpleMeterRegistry(), 64, 60);
    }
}
