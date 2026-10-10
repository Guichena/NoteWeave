package com.noteweave.source;

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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class SourceCatalogCacheTest {

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
    void shouldRoundTripVersionedCatalogWithConfiguredTtl() {
        SourceCatalogCache cache = cache(true, redisTemplate, 120, 0);
        List<SourceResponse> sources = List.of(source("source-1"));

        cache.put("workspace-1", 9, sources);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq("noteweave:v1:cache:source_catalog:workspace-1:9"),
                json.capture(),
                eq(Duration.ofSeconds(120)));
        when(valueOperations.get(anyString())).thenReturn(json.getValue());
        assertThat(cache.get("workspace-1", 9)).contains(sources);
        assertThat(metric("load")).isEqualTo(1.0);
        assertThat(metric("hit")).isEqualTo(1.0);
    }

    @Test
    void schemaOrCatalogVersionMismatchShouldFallBackToDatabase() throws Exception {
        SourceCatalogCache cache = cache(true, redisTemplate, 120, 0);
        when(valueOperations.get(anyString())).thenReturn(objectMapper.writeValueAsString(Map.of(
                "schemaVersion", "source-catalog-v0",
                "catalogVersion", 8,
                "sources", List.of()
        )));

        assertThat(cache.get("workspace-1", 9)).isEmpty();
        assertThat(metric("invalid")).isEqualTo(1.0);
    }

    @Test
    void redisFailureShouldBecomeDatabaseFallback() {
        SourceCatalogCache cache = cache(true, redisTemplate, 120, 0);
        when(valueOperations.get(anyString())).thenThrow(new IllegalStateException("redis down"));

        assertThat(cache.get("workspace-1", 1)).isEmpty();
        assertThat(metric("error")).isEqualTo(1.0);
    }

    @Test
    void missingRedisBeanShouldDisableCache() {
        SourceCatalogCache cache = cache(true, null, 120, 0);

        assertThat(cache.get("workspace-1", 1)).isEmpty();
        assertThat(metric("disabled")).isEqualTo(1.0);
    }

    private SourceCatalogCache cache(
            boolean enabled,
            StringRedisTemplate template,
            long ttlSeconds,
            long jitterSeconds
    ) {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(template);
        return new SourceCatalogCache(
                provider, objectMapper, meterRegistry, enabled, ttlSeconds, jitterSeconds);
    }

    private SourceResponse source(String sourceId) {
        return new SourceResponse(
                sourceId, "Source", "USER_UPLOAD", "READY", "PARSED", "INDEXED",
                "", "", Instant.parse("2026-07-14T10:00:00Z"), null, null, null);
    }

    private double metric(String result) {
        return meterRegistry.get("noteweave.cache.requests")
                .tag("cache", "source_catalog")
                .tag("result", result)
                .counter()
                .count();
    }
}
