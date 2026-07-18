package com.noteweave.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.noteweave.common.BusinessException;
import com.noteweave.security.CurrentUserProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class WorkloadQuotaServiceTest {

    private CurrentUserProvider currentUserProvider;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        currentUserProvider = mock(CurrentUserProvider.class);
        when(currentUserProvider.requireUserId()).thenReturn("user-sensitive");
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    void localFallbackShouldEnforceRateAndRefill() {
        MutableClock clock = new MutableClock();
        WorkloadQuotaService service = service(null, clock, 2, 60, 1, 2, 60, 1, 60);

        service.requireRate("workspace", "chat");
        service.requireRate("workspace", "chat");
        assertThatThrownBy(() -> service.requireRate("workspace", "chat"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).code())
                .isEqualTo("WORKLOAD_RATE_LIMITED");

        clock.advanceSeconds(1);
        service.requireRate("workspace", "chat");
    }

    @Test
    void localLeaseShouldRejectThenRecoverAfterExpiryOrRelease() {
        MutableClock clock = new MutableClock();
        WorkloadQuotaService service = service(null, clock, 10, 10, 4, 10, 10, 1, 5);

        service.acquireLease("workspace", "research", "task-1");
        assertThatThrownBy(() -> service.acquireLease("workspace", "research", "task-2"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).code())
                .isEqualTo("WORKLOAD_CONCURRENCY_LIMITED");

        service.releaseLease("workspace", "research", "task-1");
        service.acquireLease("workspace", "research", "task-2");
        clock.advanceSeconds(6);
        service.acquireLease("workspace", "research", "task-3");
        assertThatThrownBy(() -> service.renewLease("workspace", "research", "task-2"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).code())
                .isEqualTo("WORKLOAD_LEASE_LOST");
        assertThat(meterRegistry.get("noteweave.quota.lease.recovered")
                .tag("workload", "research")
                .tag("backend", "local")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void redisDecisionShouldBeAuthoritativeWhenAvailable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(), anyList(), any(Object[].class)))
                .thenReturn(1L)
                .thenReturn(0L);
        WorkloadQuotaService service = service(redis, new MutableClock(), 10, 10, 1, 1, 1, 1, 60);

        service.requireRate("workspace", "chat");
        assertThatThrownBy(() -> service.acquireLease("workspace", "artifact", "task-1"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).code())
                .isEqualTo("WORKLOAD_CONCURRENCY_LIMITED");
    }

    @Test
    void redisOutageAfterAcquireShouldRenewFromLocalShadowLease() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(), anyList(), any(Object[].class)))
                .thenReturn(1L)
                .thenThrow(new IllegalStateException("redis down"));
        WorkloadQuotaService service = service(redis, new MutableClock(), 10, 10, 4, 10, 10, 1, 60);

        service.acquireLease("workspace", "research", "task-1");
        service.renewLease("workspace", "research", "task-1");

        assertThat(meterRegistry.get("noteweave.quota.decisions")
                .tag("control", "renew")
                .tag("workload", "research")
                .tag("result", "granted")
                .tag("backend", "local")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void redisErrorShouldUseConservativeLocalFallback() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("redis down"));
        WorkloadQuotaService service = service(redis, new MutableClock(), 10, 10, 4, 1, 1, 1, 60);

        service.acquireLease("workspace", "artifact", "task-1");
        assertThatThrownBy(() -> service.acquireLease("workspace", "artifact", "task-2"))
                .isInstanceOf(BusinessException.class);
        assertThat(meterRegistry.get("noteweave.quota.decisions")
                .tag("control", "concurrency")
                .tag("workload", "artifact")
                .tag("result", "error")
                .tag("backend", "redis")
                .counter().count()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void scriptsAndNamespacesShouldExpressAtomicLeaseAndTokenSemantics() {
        WorkloadQuotaService service = service(null, new MutableClock(), 10, 10, 4, 10, 10, 1, 60);
        assertThat(service.keyPrefix("rate"))
                .isEqualTo("noteweave:v1:quota:test:rate:");
        assertThat(service.keyPrefix("concurrency"))
                .isEqualTo("noteweave:v1:quota:test:concurrency:");
        assertThat(WorkloadQuotaService.TOKEN_BUCKET_SCRIPT)
                .contains("HGET", "HSET", "PEXPIRE", "tokens", "updated_at");
        assertThat(WorkloadQuotaService.LEASE_ACQUIRE_SCRIPT)
                .contains("ZREMRANGEBYSCORE", "ZCARD", "ZADD", "PEXPIRE");
        assertThat(WorkloadQuotaService.LEASE_RENEW_SCRIPT)
                .contains("ZREMRANGEBYSCORE", "ZSCORE", "ZADD", "PEXPIRE")
                .doesNotContain("ZCARD");
        assertThat(WorkloadQuotaService.LEASE_RELEASE_SCRIPT).contains("ZREM");
    }

    private WorkloadQuotaService service(
            StringRedisTemplate redis,
            Clock clock,
            int rateCapacity,
            double refillPerMinute,
            int distributedConcurrency,
            int localRateCapacity,
            double localRefillPerMinute,
            int localConcurrency,
            long leaseSeconds
    ) {
        return new WorkloadQuotaService(
                redis, currentUserProvider, meterRegistry, "test", true,
                rateCapacity, refillPerMinute, distributedConcurrency,
                localRateCapacity, localRefillPerMinute, localConcurrency,
                leaseSeconds, clock);
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-07-14T10:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }
    }
}
