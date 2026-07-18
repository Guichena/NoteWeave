package com.noteweave.knowledge;

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

class WikiPageVersionCacheTest {

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
    void shouldRoundTripImmutableVersionSnapshotWithConfiguredTtl() {
        WikiPageVersionCache cache = cache(true, redisTemplate, 600, 0);
        KnowledgePageVersionSnapshot snapshot = snapshot("item-1", "version-2");

        cache.put("workspace-1", snapshot);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq("noteweave:v1:cache:wiki_page_version:workspace-1:item-1:version-2"),
                json.capture(),
                eq(Duration.ofSeconds(600)));
        when(valueOperations.get(anyString())).thenReturn(json.getValue());
        assertThat(cache.get("workspace-1", "item-1", "version-2"))
                .contains(snapshot);
        assertThat(metric("load")).isEqualTo(1.0);
        assertThat(metric("hit")).isEqualTo(1.0);
    }

    @Test
    void schemaOrIdentityMismatchShouldFallBackToDatabase() throws Exception {
        WikiPageVersionCache cache = cache(true, redisTemplate, 600, 0);
        when(valueOperations.get(anyString())).thenReturn(objectMapper.writeValueAsString(Map.of(
                "schemaVersion", "wiki-page-version-v0",
                "snapshot", snapshot("other-item", "other-version")
        )));

        assertThat(cache.get("workspace-1", "item-1", "version-2")).isEmpty();
        assertThat(metric("invalid")).isEqualTo(1.0);
    }

    @Test
    void redisFailureShouldBecomeDatabaseFallback() {
        WikiPageVersionCache cache = cache(true, redisTemplate, 600, 0);
        when(valueOperations.get(anyString())).thenThrow(new IllegalStateException("redis down"));

        assertThat(cache.get("workspace-1", "item-1", "version-2")).isEmpty();
        assertThat(metric("error")).isEqualTo(1.0);
    }

    @Test
    void missingRedisBeanShouldDisableCache() {
        WikiPageVersionCache cache = cache(true, null, 600, 0);

        assertThat(cache.get("workspace-1", "item-1", "version-2")).isEmpty();
        assertThat(metric("disabled")).isEqualTo(1.0);
    }

    private WikiPageVersionCache cache(
            boolean enabled,
            StringRedisTemplate template,
            long ttlSeconds,
            long jitterSeconds
    ) {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(template);
        return new WikiPageVersionCache(
                provider, objectMapper, meterRegistry, enabled, ttlSeconds, jitterSeconds);
    }

    private KnowledgePageVersionSnapshot snapshot(String itemId, String versionId) {
        return new KnowledgePageVersionSnapshot(
                itemId,
                versionId,
                2,
                "content",
                "summary",
                null,
                List.of(new KnowledgeCitationResponse(
                        "citation-1", "source-1", "Source", "quote", 1,
                        "page:1", "", "")),
                Instant.parse("2026-07-14T10:00:00Z"));
    }

    private double metric(String result) {
        return meterRegistry.get("noteweave.cache.requests")
                .tag("cache", "wiki_page_version")
                .tag("result", result)
                .counter()
                .count();
    }
}
