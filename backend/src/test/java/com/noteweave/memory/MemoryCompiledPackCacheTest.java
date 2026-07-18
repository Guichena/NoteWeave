package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class MemoryCompiledPackCacheTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ObjectMapper objectMapper;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        meterRegistry = new SimpleMeterRegistry();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void shouldRoundTripFingerprintKeyWithConfiguredTtl() {
        MemoryCompiledPackCache cache = cache(true, redisTemplate, 180, 0);
        MemoryCompiledPackCache.CacheKey key = key("state-a");
        MemoryControlPackResponse pack = pack();

        cache.put(key, pack);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq("noteweave:v1:cache:memory_compiled_pack:workspace:actor:chat:request:memory-compiler-policy-v1:state-a"),
                json.capture(),
                eq(Duration.ofSeconds(180)));
        when(valueOperations.get(anyString())).thenReturn(json.getValue());
        assertThat(cache.get(key)).contains(pack);
        assertThat(metric("load")).isEqualTo(1.0);
        assertThat(metric("hit")).isEqualTo(1.0);
    }

    @Test
    void schemaOrFingerprintMismatchShouldFallBackToCompiler() throws Exception {
        MemoryCompiledPackCache cache = cache(true, redisTemplate, 180, 0);
        when(valueOperations.get(anyString())).thenReturn(objectMapper.writeValueAsString(Map.of(
                "schemaVersion", "memory-compiled-pack-v0",
                "cacheKey", key("other-state"),
                "pack", pack()
        )));

        assertThat(cache.get(key("state-a"))).isEmpty();
        assertThat(metric("invalid")).isEqualTo(1.0);
    }

    @Test
    void redisFailureShouldBecomeCompilerFallback() {
        MemoryCompiledPackCache cache = cache(true, redisTemplate, 180, 0);
        when(valueOperations.get(anyString())).thenThrow(new IllegalStateException("redis down"));

        assertThat(cache.get(key("state-a"))).isEmpty();
        assertThat(metric("error")).isEqualTo(1.0);
    }

    @Test
    void missingRedisBeanShouldDisableCache() {
        MemoryCompiledPackCache cache = cache(true, null, 180, 0);

        assertThat(cache.get(key("state-a"))).isEmpty();
        assertThat(metric("disabled")).isEqualTo(1.0);
    }

    private MemoryCompiledPackCache cache(
            boolean enabled,
            StringRedisTemplate template,
            long ttlSeconds,
            long jitterSeconds
    ) {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(template);
        return new MemoryCompiledPackCache(
                provider, objectMapper, meterRegistry, enabled, ttlSeconds, jitterSeconds);
    }

    private MemoryCompiledPackCache.CacheKey key(String stateFingerprint) {
        return new MemoryCompiledPackCache.CacheKey(
                "workspace", "actor", "chat", "request",
                MemoryCompilerPolicy.VERSION, stateFingerprint);
    }

    private MemoryControlPackResponse pack() {
        return new MemoryControlPackResponse(
                "chat", "QA", "CHAT_QA",
                List.of("简洁"), List.of(), List.of(), List.of(),
                List.of("Memory 不作为事实来源"), List.of(), List.of(),
                List.of("memory-1"),
                List.of(new MemoryReferenceResponse("memory-1", "version-1", 0.8)),
                new MemoryCompilationTraceResponse(
                        MemoryCompilerPolicy.VERSION, 320, 20, 1, 1, 0,
                        false, false, List.of()));
    }

    private double metric(String result) {
        return meterRegistry.get("noteweave.cache.requests")
                .tag("cache", "memory_compiled_pack")
                .tag("result", result)
                .counter()
                .count();
    }
}
