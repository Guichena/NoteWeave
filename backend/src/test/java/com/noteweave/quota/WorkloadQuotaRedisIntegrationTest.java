package com.noteweave.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.noteweave.common.BusinessException;
import com.noteweave.security.CurrentUserProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@EnabledIfEnvironmentVariable(named = "NOTEWEAVE_REDIS_INTEGRATION", matches = "true")
class WorkloadQuotaRedisIntegrationTest {

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private WorkloadQuotaService first;
    private WorkloadQuotaService second;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        when(currentUserProvider.requireUserId()).thenReturn("shared-user");
        first = service(currentUserProvider);
        second = service(currentUserProvider);
        deleteKeys();
    }

    @AfterEach
    void tearDown() {
        deleteKeys();
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void shouldShareRateAndLeaseStateAcrossInstancesAndRecoverExpiredLease() throws InterruptedException {
        first.requireRate("workspace", "chat");
        second.requireRate("workspace", "chat");
        assertThatThrownBy(() -> first.requireRate("workspace", "chat"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).code())
                .isEqualTo("WORKLOAD_RATE_LIMITED");

        first.acquireLease("workspace", "research", "task-1");
        assertThatThrownBy(() -> second.acquireLease("workspace", "research", "task-2"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).code())
                .isEqualTo("WORKLOAD_CONCURRENCY_LIMITED");

        awaitLeaseExpiryAndAcquire();
        assertThatThrownBy(() -> first.renewLease("workspace", "research", "task-1"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).code())
                .isEqualTo("WORKLOAD_LEASE_LOST");

        String concurrencyKey = second.keyPrefix("concurrency") + "workspace:research";
        assertThat(redisTemplate.opsForZSet().range(concurrencyKey, 0, -1))
                .containsExactly("task-2");
    }

    private WorkloadQuotaService service(CurrentUserProvider currentUserProvider) {
        return new WorkloadQuotaService(
                redisTemplate, currentUserProvider, new SimpleMeterRegistry(),
                "integration", true, 2, 0.000001, 1,
                1, 0.000001, 1, 1, Clock.systemUTC());
    }

    private void awaitLeaseExpiryAndAcquire() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            try {
                second.acquireLease("workspace", "research", "task-2");
                return;
            } catch (BusinessException exception) {
                assertThat(exception.code()).isEqualTo("WORKLOAD_CONCURRENCY_LIMITED");
                if (System.nanoTime() >= deadline) {
                    throw exception;
                }
                Thread.sleep(50);
            }
        }
    }

    private void deleteKeys() {
        if (redisTemplate == null) {
            return;
        }
        Set<String> keys = redisTemplate.keys("noteweave:v1:quota:integration:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

}
