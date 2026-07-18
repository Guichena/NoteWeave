package com.noteweave.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class SourceCatalogCache {

    static final String SCHEMA_VERSION = "source-catalog-v1";
    static final String KEY_PREFIX = "noteweave:v1:cache:source_catalog:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    private final Duration ttl;
    private final long jitterSeconds;

    public SourceCatalogCache(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${noteweave.cache.source-catalog.enabled:true}") boolean enabled,
            @Value("${noteweave.cache.source-catalog.ttl-seconds:120}") long ttlSeconds,
            @Value("${noteweave.cache.source-catalog.jitter-seconds:30}") long jitterSeconds
    ) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.enabled = enabled && this.redisTemplate != null;
        this.ttl = Duration.ofSeconds(Math.max(1, ttlSeconds));
        this.jitterSeconds = Math.max(0, jitterSeconds);
    }

    public Optional<List<SourceResponse>> get(String workspaceId, long catalogVersion) {
        if (!enabled) {
            count("disabled");
            return Optional.empty();
        }
        try {
            String json = redisTemplate.opsForValue().get(key(workspaceId, catalogVersion));
            if (json == null) {
                count("miss");
                return Optional.empty();
            }
            SourceCatalogSnapshot snapshot = objectMapper.readValue(json, SourceCatalogSnapshot.class);
            if (!SCHEMA_VERSION.equals(snapshot.schemaVersion())
                    || snapshot.catalogVersion() != catalogVersion) {
                count("invalid");
                return Optional.empty();
            }
            count("hit");
            return Optional.of(List.copyOf(snapshot.sources()));
        } catch (Exception exception) {
            count("error");
            return Optional.empty();
        }
    }

    public void put(String workspaceId, long catalogVersion, List<SourceResponse> sources) {
        if (!enabled) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(new SourceCatalogSnapshot(
                    SCHEMA_VERSION, catalogVersion, List.copyOf(sources)));
            redisTemplate.opsForValue().set(
                    key(workspaceId, catalogVersion), json, withJitter(ttl));
            count("load");
        } catch (Exception exception) {
            count("error");
        }
    }

    private String key(String workspaceId, long catalogVersion) {
        return KEY_PREFIX + workspaceId + ":" + catalogVersion;
    }

    private Duration withJitter(Duration base) {
        if (jitterSeconds == 0) {
            return base;
        }
        return base.plusSeconds(ThreadLocalRandom.current().nextLong(jitterSeconds + 1));
    }

    private void count(String result) {
        meterRegistry.counter(
                "noteweave.cache.requests", "cache", "source_catalog", "result", result)
                .increment();
    }

    private record SourceCatalogSnapshot(
            String schemaVersion,
            long catalogVersion,
            List<SourceResponse> sources
    ) {
        private SourceCatalogSnapshot {
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }
}
