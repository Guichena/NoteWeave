package com.noteweave.memory;

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
public class MemoryCompiledPackCache {

    static final String SCHEMA_VERSION = "memory-compiled-pack-v1";
    static final String KEY_PREFIX = "noteweave:v1:cache:memory_compiled_pack:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    private final Duration ttl;
    private final long jitterSeconds;

    public MemoryCompiledPackCache(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${noteweave.cache.memory-compiled-pack.enabled:true}") boolean enabled,
            @Value("${noteweave.cache.memory-compiled-pack.ttl-seconds:180}") long ttlSeconds,
            @Value("${noteweave.cache.memory-compiled-pack.jitter-seconds:30}") long jitterSeconds
    ) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.enabled = enabled && this.redisTemplate != null;
        this.ttl = Duration.ofSeconds(Math.max(1, ttlSeconds));
        this.jitterSeconds = Math.max(0, jitterSeconds);
    }

    public Optional<MemoryControlPackResponse> get(CacheKey cacheKey) {
        if (!enabled) {
            count("disabled");
            return Optional.empty();
        }
        try {
            String json = redisTemplate.opsForValue().get(key(cacheKey));
            if (json == null) {
                count("miss");
                return Optional.empty();
            }
            CacheEnvelope envelope = objectMapper.readValue(json, CacheEnvelope.class);
            if (!SCHEMA_VERSION.equals(envelope.schemaVersion())
                    || !cacheKey.equals(envelope.cacheKey())
                    || envelope.pack() == null) {
                count("invalid");
                return Optional.empty();
            }
            count("hit");
            return Optional.of(envelope.pack());
        } catch (Exception exception) {
            count("error");
            return Optional.empty();
        }
    }

    public void put(CacheKey cacheKey, MemoryControlPackResponse pack) {
        if (!enabled) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(
                    new CacheEnvelope(SCHEMA_VERSION, cacheKey, pack));
            redisTemplate.opsForValue().set(key(cacheKey), json, withJitter(ttl));
            count("load");
        } catch (Exception exception) {
            count("error");
        }
    }

    private String key(CacheKey key) {
        return KEY_PREFIX
                + key.workspaceId() + ":"
                + key.actorFingerprint() + ":"
                + key.packType() + ":"
                + key.requestFingerprint() + ":"
                + key.policyVersion() + ":"
                + key.stateFingerprint();
    }

    private Duration withJitter(Duration base) {
        if (jitterSeconds == 0) {
            return base;
        }
        return base.plusSeconds(ThreadLocalRandom.current().nextLong(jitterSeconds + 1));
    }

    private void count(String result) {
        meterRegistry.counter(
                "noteweave.cache.requests", "cache", "memory_compiled_pack", "result", result)
                .increment();
    }

    public record CacheKey(
            String workspaceId,
            String actorFingerprint,
            String packType,
            String requestFingerprint,
            String policyVersion,
            String stateFingerprint
    ) {
    }

    private record CacheEnvelope(
            String schemaVersion,
            CacheKey cacheKey,
            MemoryControlPackResponse pack
    ) {
    }
}
