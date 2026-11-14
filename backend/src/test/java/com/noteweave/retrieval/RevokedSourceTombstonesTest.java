package com.noteweave.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.source.SourceDeletedEvent;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

class RevokedSourceTombstonesTest {

    @SuppressWarnings("unchecked")
    private final ZSetOperations<String, String> zset = mock(ZSetOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @Test
    void deletionWritesATombstoneThatExpiresAfterTheConfiguredWindow() {
        when(redis.opsForZSet()).thenReturn(zset);
        RevokedSourceTombstones tombstones = new RevokedSourceTombstones(redis, null, Duration.ofHours(24));
        long before = System.currentTimeMillis();

        tombstones.onSourceDeleted(new SourceDeletedEvent("ws-1", "source-1", List.of()));

        org.mockito.ArgumentCaptor<Double> score = org.mockito.ArgumentCaptor.forClass(Double.class);
        verify(zset).add(eq(RevokedSourceTombstones.key("ws-1")), eq("source-1"), score.capture());
        assertThat(score.getValue()).isBetween(before + Duration.ofHours(24).toMillis() - 1000d,
                System.currentTimeMillis() + Duration.ofHours(24).toMillis() + 1000d);
        verify(redis).expire(RevokedSourceTombstones.key("ws-1"), Duration.ofHours(25));
    }

    @Test
    void readingDropsExpiredTombstonesAndReturnsTheLiveOnes() {
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.rangeByScore(eq(RevokedSourceTombstones.key("ws-1")), anyDouble(), anyDouble()))
                .thenReturn(new LinkedHashSet<>(List.of("source-1", "source-2")));
        RevokedSourceTombstones tombstones = new RevokedSourceTombstones(redis, null, Duration.ofHours(24));

        assertThat(tombstones.revokedSourceIds("ws-1")).containsExactly("source-1", "source-2");
        verify(zset).removeRangeByScore(eq(RevokedSourceTombstones.key("ws-1")),
                eq(Double.NEGATIVE_INFINITY), anyDouble());
    }

    @Test
    void redisFailuresNeverBlockDeletionOrRetrieval() {
        when(redis.opsForZSet()).thenReturn(zset);
        doThrow(new IllegalStateException("redis down")).when(zset).add(anyString(), anyString(), anyDouble());
        when(zset.rangeByScore(anyString(), anyDouble(), anyDouble())).thenThrow(new IllegalStateException("down"));
        RevokedSourceTombstones tombstones = new RevokedSourceTombstones(redis, null, Duration.ofHours(24));

        tombstones.record("ws-1", "source-1");
        // 读不到墓碑时不排除，已撤销资料由召回后的 MySQL 复核拒绝
        assertThat(tombstones.revokedSourceIds("ws-1")).isEmpty();
        assertThat(new RevokedSourceTombstones(null, null, Duration.ofHours(1)).revokedSourceIds("ws-1"))
                .isEqualTo(Set.of());
    }
}
