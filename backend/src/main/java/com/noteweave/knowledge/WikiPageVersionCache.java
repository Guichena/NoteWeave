package com.noteweave.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class WikiPageVersionCache {

    static final String SCHEMA_VERSION = "wiki-page-version-v1";
    static final String KEY_PREFIX = "noteweave:v1:cache:wiki_page_version:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    private final Duration ttl;
    private final long jitterSeconds;

    public WikiPageVersionCache(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${noteweave.cache.wiki-page-version.enabled:true}") boolean enabled,
            @Value("${noteweave.cache.wiki-page-version.ttl-seconds:600}") long ttlSeconds,
            @Value("${noteweave.cache.wiki-page-version.jitter-seconds:120}") long jitterSeconds
    ) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.enabled = enabled && this.redisTemplate != null;
        this.ttl = Duration.ofSeconds(Math.max(1, ttlSeconds));
        this.jitterSeconds = Math.max(0, jitterSeconds);
    }

    public Optional<KnowledgePageVersionSnapshot> get(
            String workspaceId,
            String itemId,
            String versionId
    ) {
        if (!enabled) {
            count("disabled");
            return Optional.empty();
        }
        try {
            String json = redisTemplate.opsForValue().get(key(workspaceId, itemId, versionId));
            if (json == null) {
                count("miss");
                return Optional.empty();
            }
            CacheEnvelope envelope = objectMapper.readValue(json, CacheEnvelope.class);
            KnowledgePageVersionSnapshot snapshot = envelope.snapshot();
            if (!SCHEMA_VERSION.equals(envelope.schemaVersion())
                    || snapshot == null
                    || !itemId.equals(snapshot.itemId())
                    || !versionId.equals(snapshot.versionId())) {
                count("invalid");
                return Optional.empty();
            }
            count("hit");
            return Optional.of(snapshot);
        } catch (Exception exception) {
            count("error");
            return Optional.empty();
        }
    }

    public void put(
            String workspaceId,
            KnowledgePageVersionSnapshot snapshot
    ) {
        if (!enabled) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(
                    new CacheEnvelope(SCHEMA_VERSION, snapshot));
            redisTemplate.opsForValue().set(
                    key(workspaceId, snapshot.itemId(), snapshot.versionId()),
                    json,
                    withJitter(ttl));
            count("load");
        } catch (Exception exception) {
            count("error");
        }
    }

    private String key(String workspaceId, String itemId, String versionId) {
        return KEY_PREFIX + workspaceId + ":" + itemId + ":" + versionId;
    }

    private Duration withJitter(Duration base) {
        if (jitterSeconds == 0) {
            return base;
        }
        return base.plusSeconds(ThreadLocalRandom.current().nextLong(jitterSeconds + 1));
    }

    private void count(String result) {
        meterRegistry.counter(
                "noteweave.cache.requests", "cache", "wiki_page_version", "result", result)
                .increment();
    }

    private record CacheEnvelope(
            String schemaVersion,
            KnowledgePageVersionSnapshot snapshot
    ) {
    }
}
