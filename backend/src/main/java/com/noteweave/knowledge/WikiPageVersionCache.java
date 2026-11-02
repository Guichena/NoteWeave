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
            String cacheKey = key(workspaceId, snapshot.itemId(), snapshot.versionId());
            // 撤销与写入使用同一原子屏障，防止删除缓存后在途旧加载重新写回正文。
            var script = new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
                    "if redis.call('EXISTS',KEYS[2])==1 then return 0 end "
                    + "redis.call('SET',KEYS[1],ARGV[1],'PX',ARGV[2]); return 1", Long.class);
            redisTemplate.execute(script, java.util.List.of(cacheKey, cacheKey + ":revoked"),
                    json, Long.toString(withJitter(ttl).toMillis()));
            count("load");
        } catch (Exception exception) {
            count("error");
        }
    }

    /** 合规撤销按完整身份主动失效，失败向上传播，不能声称已物理清理。 */
    public void invalidate(String workspaceId, String itemId, String versionId) {
        if (redisTemplate == null) return;
        String cacheKey = key(workspaceId, itemId, versionId);
        var script = new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
                "redis.call('SET',KEYS[2],'1'); return redis.call('DEL',KEYS[1])", Long.class);
        redisTemplate.execute(script, java.util.List.of(cacheKey, cacheKey + ":revoked"));
    }

    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private String purgeCursor = "";

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setJdbcTemplate(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** DB合规撤销事实作为持久重试依据，按版本ID有界遍历，Redis恢复后补清且不依赖TTL。 */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${noteweave.cache.wiki-revocation-retry-ms:60000}")
    public synchronized void retryComplianceInvalidation() {
        if (jdbcTemplate == null || redisTemplate == null) return;
        var versions = jdbcTemplate.query("""
                select distinct v.id, v.item_id, i.workspace_id
                from knowledge_version v join knowledge_item i on i.id=v.item_id
                join knowledge_version_citation kvc on kvc.knowledge_version_id=v.id
                join citation c on c.id=kvc.citation_id
                join source s on s.id=c.source_id and s.workspace_id=i.workspace_id
                where s.status='DELETED' and s.delete_reason='COMPLIANCE' and v.id>?
                order by v.id limit 100
                """, (rs,n) -> new String[] {rs.getString(1),rs.getString(2),rs.getString(3)}, purgeCursor);
        if (versions.isEmpty()) { purgeCursor = ""; return; }
        for (String[] version : versions) {
            try {
                invalidate(version[2], version[1], version[0]);
                purgeCursor = version[0];
            } catch (RuntimeException failure) {
                count("revocation_retry_failed");
                return;
            }
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
