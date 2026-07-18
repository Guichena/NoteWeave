package com.noteweave.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class WorkspaceAclCacheTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        meterRegistry = new SimpleMeterRegistry();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void shouldUseVersionedNamespaceAndConfiguredPositiveTtl() {
        WorkspaceAclCache cache = cache(true, redisTemplate, 300, 30, 0);

        cache.put("workspace-1", "user-1", 7, WorkspaceRole.EDITOR);

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(
                eq("noteweave:v1:cache:acl:workspace-1:user-1:7"),
                eq("ROLE:EDITOR"),
                ttl.capture());
        assertThat(ttl.getValue()).isEqualTo(Duration.ofSeconds(300));
        assertThat(metric("load")).isEqualTo(1.0);
    }

    @Test
    void shouldCacheDeniedMembershipWithShortTtl() {
        WorkspaceAclCache cache = cache(true, redisTemplate, 300, 25, 0);

        cache.putDenied("workspace-1", "outsider", 4);

        verify(valueOperations).set(
                "noteweave:v1:cache:acl:workspace-1:outsider:4",
                "DENIED",
                Duration.ofSeconds(25));
        when(valueOperations.get(anyString())).thenReturn("DENIED");
        WorkspaceAclCache.Lookup lookup = cache.get("workspace-1", "outsider", 4);
        assertThat(lookup.denied()).isTrue();
        assertThat(lookup.role()).isEmpty();
        assertThat(metric("negative_hit")).isEqualTo(1.0);
    }

    @Test
    void shouldReadNamespacedRoleAndAcceptLegacyRoleValue() {
        WorkspaceAclCache cache = cache(true, redisTemplate, 300, 30, 0);
        when(valueOperations.get(anyString()))
                .thenReturn("ROLE:VIEWER")
                .thenReturn("OWNER");

        assertThat(cache.get("workspace-1", "user-1", 1).role())
                .contains(WorkspaceRole.VIEWER);
        assertThat(cache.get("workspace-1", "user-1", 1).role())
                .contains(WorkspaceRole.OWNER);
        assertThat(metric("hit")).isEqualTo(2.0);
    }

    @Test
    void redisFailureShouldBecomeMissForDatabaseFallback() {
        WorkspaceAclCache cache = cache(true, redisTemplate, 300, 30, 0);
        when(valueOperations.get(anyString())).thenThrow(new IllegalStateException("redis down"));

        WorkspaceAclCache.Lookup lookup = cache.get("workspace-1", "user-1", 1);

        assertThat(lookup.denied()).isFalse();
        assertThat(lookup.role()).isEmpty();
        assertThat(metric("error")).isEqualTo(1.0);
    }

    @Test
    void missingRedisBeanShouldDisableCacheWithoutDenyingAccess() {
        WorkspaceAclCache cache = cache(true, null, 300, 30, 0);

        WorkspaceAclCache.Lookup lookup = cache.get("workspace-1", "user-1", 1);

        assertThat(lookup.denied()).isFalse();
        assertThat(lookup.role()).isEmpty();
        assertThat(metric("disabled")).isEqualTo(1.0);
    }

    private WorkspaceAclCache cache(
            boolean enabled,
            StringRedisTemplate template,
            long ttlSeconds,
            long negativeTtlSeconds,
            long jitterSeconds
    ) {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(template);
        return new WorkspaceAclCache(
                provider, meterRegistry, enabled, ttlSeconds, negativeTtlSeconds, jitterSeconds);
    }

    private double metric(String result) {
        return meterRegistry.get("noteweave.security.acl.cache")
                .tag("result", result)
                .counter()
                .count();
    }
}
