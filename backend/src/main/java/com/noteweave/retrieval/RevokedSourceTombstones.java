package com.noteweave.retrieval;

import com.noteweave.source.SourceDeletedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 资料撤销墓碑：撤销提交后按工作台写入 Redis 有序集合，分值是墓碑到期时间。
 * <p>
 * ES 里的片段由清理任务异步删除，失败会按退避重试；删除完成之前，旧片段仍可能被召回并占掉 TopK。
 * 检索时据墓碑在 ES 查询里提前排除这些资料。墓碑保留一段时间（默认 24 小时），清理任务每次重试都会续期，
 * 也覆盖删除之后迟到的投影写入。Redis 不可用时不排除，召回后的 MySQL 复核仍会拒绝已撤销资料。
 */
@Component
public class RevokedSourceTombstones implements RevokedSourceFilter {

    private static final Logger log = LoggerFactory.getLogger(RevokedSourceTombstones.class);
    static final String KEY_PREFIX = "noteweave:v1:retrieval:revoked_sources:";

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;
    private final Duration ttl;

    @org.springframework.beans.factory.annotation.Autowired
    public RevokedSourceTombstones(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            @Value("${noteweave.retrieval.revoked-source-tombstone-hours:24}") long ttlHours
    ) {
        this(redisTemplateProvider.getIfAvailable(), meterRegistryProvider.getIfAvailable(),
                Duration.ofHours(Math.max(1, ttlHours)));
    }

    RevokedSourceTombstones(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry, Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        this.ttl = ttl;
    }

    /** 撤销提交之后写墓碑：回滚的删除不能让仍然有效的资料被排除。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSourceDeleted(SourceDeletedEvent event) {
        record(event.workspaceId(), event.sourceId());
    }

    /** 写入或续期一条墓碑；失败只记指标，不影响撤销本身。 */
    public void record(String workspaceId, String sourceId) {
        if (redisTemplate == null || blank(workspaceId) || blank(sourceId)) return;
        try {
            String key = key(workspaceId);
            long expiresAt = System.currentTimeMillis() + ttl.toMillis();
            redisTemplate.opsForZSet().add(key, sourceId, expiresAt);
            redisTemplate.expire(key, ttl.plusHours(1));
            count("recorded");
        } catch (RuntimeException ex) {
            count("record_failed");
            log.warn("Revoked source tombstone write failed: workspaceId={}, sourceId={}: {}",
                    workspaceId, sourceId, ex.getClass().getSimpleName());
        }
    }

    @Override
    public Set<String> revokedSourceIds(String workspaceId) {
        if (redisTemplate == null || blank(workspaceId)) return Set.of();
        try {
            String key = key(workspaceId);
            long now = System.currentTimeMillis();
            redisTemplate.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, now);
            Set<String> members = redisTemplate.opsForZSet().rangeByScore(key, now, Double.POSITIVE_INFINITY);
            return members == null ? Set.of() : new LinkedHashSet<>(members);
        } catch (RuntimeException ex) {
            count("read_failed");
            return Set.of();
        }
    }

    static String key(String workspaceId) {
        return KEY_PREFIX + workspaceId;
    }

    private void count(String outcome) {
        if (meterRegistry != null) {
            meterRegistry.counter("noteweave.retrieval.revoked_source_tombstone", "outcome", outcome).increment();
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
