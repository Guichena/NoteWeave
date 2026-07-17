package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@EnabledIfEnvironmentVariable(named = "NOTEWEAVE_REDIS_INTEGRATION", matches = "true")
class ResearchAgentRateLimitRedisIntegrationTest {
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory("localhost", 6379);
        factory.afterPropertiesSet(); factory.start();
        redis = new StringRedisTemplate(factory); redis.afterPropertiesSet();
        deleteKeys();
    }

    @AfterEach
    void tearDown() { deleteKeys(); if (factory != null) factory.destroy(); }

    @Test
    void shouldAtomicallyShareProviderWorkspaceAndRunCapacityAcrossInstances() {
        ResearchAgentRateLimitService first = service();
        ResearchAgentRateLimitService second = service();
        ResearchAgentRateLimitService.PermitRequest permit = new ResearchAgentRateLimitService.PermitRequest(
                "provider-a", "workspace-a", "run-a", "DEEP_CELL");

        first.requirePermit(permit);

        assertThatThrownBy(() -> second.requirePermit(permit))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_RATE_LIMITED");
    }

    private ResearchAgentRateLimitService service() {
        return new ResearchAgentRateLimitService(redis, new SimpleMeterRegistry(), true, false, 1, 0.000001);
    }
    private void deleteKeys() { if (redis != null) { Set<String> keys = redis.keys("noteweave:v1:research:*"); if (keys != null && !keys.isEmpty()) redis.delete(keys); } }
}
