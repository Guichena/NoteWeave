package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.noteweave.common.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

class ResearchAgentRateLimitServiceTest {

    private static final ResearchAgentRateLimitService.PermitRequest REQUEST =
            new ResearchAgentRateLimitService.PermitRequest(
                    "provider-a", "workspace-a", "run-a", "DEEP_CELL");

    @Test
    void shouldFailClosedWhenRedisIsUnavailableByDefault() {
        ResearchAgentRateLimitService service = new ResearchAgentRateLimitService(
                (StringRedisTemplate) null, new SimpleMeterRegistry(), true, false, 10, 10.0
        );

        assertThatThrownBy(() -> service.requirePermit(new ResearchAgentRateLimitService.PermitRequest(
                "provider-a", "workspace-a", "run-a", "DEEP_CELL"
        ))).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_RATE_LIMIT_UNAVAILABLE");
    }

    @Test
    void shouldRecordARestrictedPermitWhenRedisCapacityIsExhausted() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doReturn(0L).when(redis).execute(
                any(DefaultRedisScript.class), anyList(), any(Object[].class));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ResearchAgentRateLimitService service = new ResearchAgentRateLimitService(
                redis, meters, true, false, 1, 1.0);

        assertThatThrownBy(() -> service.requirePermit(new ResearchAgentRateLimitService.PermitRequest(
                "provider-a", "workspace-a", "run-a", "DEEP_CELL")))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_RATE_LIMITED");
        assertThat(meters.counter("noteweave.research.agent.permit", "result", "limited").count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldUseRedisServerTimeWithoutJvmTimestampArgument() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doReturn(1L).when(redis).execute(
                any(DefaultRedisScript.class), anyList(), any(Object[].class));
        ResearchAgentRateLimitService service = new ResearchAgentRateLimitService(
                redis, new SimpleMeterRegistry(), true, false, 10, 10.0);

        service.requirePermit(REQUEST);

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(any(DefaultRedisScript.class), anyList(), arguments.capture());
        assertThat(arguments.getValue()).containsExactly("10", Double.toString(10.0 / 60_000.0), "120000");
        assertThat(ResearchAgentRateLimitService.PERMIT_SCRIPT)
                .contains("redis.call('TIME')")
                .doesNotContain("local now = tonumber(ARGV");
    }
}
