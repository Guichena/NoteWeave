package com.noteweave.quota;

import com.noteweave.common.BusinessException;
import com.noteweave.security.CurrentUserProvider;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class WorkloadQuotaService {

    static final String KEY_PREFIX = "noteweave:v1:quota:";
    static final String TOKEN_BUCKET_SCRIPT = """
            local redis_time = redis.call('TIME')
            local now = (tonumber(redis_time[1]) * 1000) + math.floor(tonumber(redis_time[2]) / 1000)
            local capacity = tonumber(ARGV[1])
            local refill_per_ms = tonumber(ARGV[2])
            local ttl_ms = tonumber(ARGV[3])
            local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens'))
            local updated_at = tonumber(redis.call('HGET', KEYS[1], 'updated_at'))
            if tokens == nil then
              tokens = capacity
              updated_at = now
            end
            if now > updated_at then
              tokens = math.min(capacity, tokens + ((now - updated_at) * refill_per_ms))
              updated_at = now
            end
            local allowed = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            end
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'updated_at', updated_at)
            redis.call('PEXPIRE', KEYS[1], ttl_ms)
            return allowed
            """;
    static final String LEASE_ACQUIRE_SCRIPT = """
            local redis_time = redis.call('TIME')
            local now = (tonumber(redis_time[1]) * 1000) + math.floor(tonumber(redis_time[2]) / 1000)
            local limit = tonumber(ARGV[1])
            local lease_ms = tonumber(ARGV[2])
            local ttl_ms = tonumber(ARGV[3])
            local lease_token = ARGV[4]
            local expires_at = now + lease_ms
            local recovered = redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
            if redis.call('ZSCORE', KEYS[1], lease_token) then
              redis.call('ZADD', KEYS[1], expires_at, lease_token)
              redis.call('PEXPIRE', KEYS[1], ttl_ms)
              return (recovered * 2) + 1
            end
            if redis.call('ZCARD', KEYS[1]) >= limit then
              return recovered * 2
            end
            redis.call('ZADD', KEYS[1], expires_at, lease_token)
            redis.call('PEXPIRE', KEYS[1], ttl_ms)
            return (recovered * 2) + 1
            """;
    static final String LEASE_RENEW_SCRIPT = """
            local redis_time = redis.call('TIME')
            local now = (tonumber(redis_time[1]) * 1000) + math.floor(tonumber(redis_time[2]) / 1000)
            local lease_ms = tonumber(ARGV[1])
            local ttl_ms = tonumber(ARGV[2])
            local lease_token = ARGV[3]
            local expires_at = now + lease_ms
            local recovered = redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
            if not redis.call('ZSCORE', KEYS[1], lease_token) then
              return recovered * 2
            end
            redis.call('ZADD', KEYS[1], expires_at, lease_token)
            redis.call('PEXPIRE', KEYS[1], ttl_ms)
            return (recovered * 2) + 1
            """;
    static final String LEASE_RELEASE_SCRIPT = """
            return redis.call('ZREM', KEYS[1], ARGV[1])
            """;

    private static final DefaultRedisScript<Long> TOKEN_BUCKET =
            new DefaultRedisScript<>(TOKEN_BUCKET_SCRIPT, Long.class);
    private static final DefaultRedisScript<Long> LEASE_ACQUIRE =
            new DefaultRedisScript<>(LEASE_ACQUIRE_SCRIPT, Long.class);
    private static final DefaultRedisScript<Long> LEASE_RENEW =
            new DefaultRedisScript<>(LEASE_RENEW_SCRIPT, Long.class);
    private static final DefaultRedisScript<Long> LEASE_RELEASE =
            new DefaultRedisScript<>(LEASE_RELEASE_SCRIPT, Long.class);
    private static final int LOCAL_LEASE_CLEANUP_BATCH_SIZE = 16;

    private final StringRedisTemplate redisTemplate;
    private final CurrentUserProvider currentUserProvider;
    private final MeterRegistry meterRegistry;
    private final String environment;
    private final boolean enabled;
    private final int rateCapacity;
    private final double refillPerMillisecond;
    private final int distributedConcurrencyLimit;
    private final int localRateCapacity;
    private final double localRefillPerMillisecond;
    private final int localConcurrencyLimit;
    private final long leaseMilliseconds;
    private final boolean localFallbackEnabled;
    private final Clock clock;
    private final Map<String, LocalBucket> localBuckets = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Long>> localLeases = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> localLeaseCleanupQueue = new ConcurrentLinkedQueue<>();
    private final Set<String> scheduledLocalLeaseKeys = ConcurrentHashMap.newKeySet();

    @Autowired
    public WorkloadQuotaService(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            CurrentUserProvider currentUserProvider,
            MeterRegistry meterRegistry,
            @Value("${noteweave.environment:local}") String environment,
            @Value("${noteweave.quota.enabled:true}") boolean enabled,
            @Value("${noteweave.quota.rate.capacity:20}") int rateCapacity,
            @Value("${noteweave.quota.rate.refill-per-minute:20}") double refillPerMinute,
            @Value("${noteweave.quota.concurrency.limit:4}") int distributedConcurrencyLimit,
            @Value("${noteweave.quota.local.rate.capacity:5}") int localRateCapacity,
            @Value("${noteweave.quota.local.rate.refill-per-minute:5}") double localRefillPerMinute,
            @Value("${noteweave.quota.local.concurrency.limit:1}") int localConcurrencyLimit,
            @Value("${noteweave.quota.concurrency.lease-seconds:300}") long leaseSeconds,
            @Value("${noteweave.quota.local-fallback-enabled:false}") boolean localFallbackEnabled
    ) {
        this(redisTemplateProvider.getIfAvailable(), currentUserProvider, meterRegistry, environment, enabled,
                rateCapacity, refillPerMinute, distributedConcurrencyLimit,
                localRateCapacity, localRefillPerMinute, localConcurrencyLimit,
                leaseSeconds, localFallbackEnabled, Clock.systemUTC());
    }

    WorkloadQuotaService(
            StringRedisTemplate redisTemplate,
            CurrentUserProvider currentUserProvider,
            MeterRegistry meterRegistry,
            String environment,
            boolean enabled,
            int rateCapacity,
            double refillPerMinute,
            int distributedConcurrencyLimit,
            int localRateCapacity,
            double localRefillPerMinute,
            int localConcurrencyLimit,
            long leaseSeconds,
            Clock clock
    ) {
        this(redisTemplate, currentUserProvider, meterRegistry, environment, enabled,
                rateCapacity, refillPerMinute, distributedConcurrencyLimit,
                localRateCapacity, localRefillPerMinute, localConcurrencyLimit,
                leaseSeconds, false, clock);
    }

    WorkloadQuotaService(
            StringRedisTemplate redisTemplate,
            CurrentUserProvider currentUserProvider,
            MeterRegistry meterRegistry,
            String environment,
            boolean enabled,
            int rateCapacity,
            double refillPerMinute,
            int distributedConcurrencyLimit,
            int localRateCapacity,
            double localRefillPerMinute,
            int localConcurrencyLimit,
            long leaseSeconds,
            boolean localFallbackEnabled,
            Clock clock
    ) {
        this.redisTemplate = redisTemplate;
        this.currentUserProvider = currentUserProvider;
        this.meterRegistry = meterRegistry;
        this.environment = namespace(environment);
        this.enabled = enabled;
        this.rateCapacity = Math.max(1, rateCapacity);
        this.refillPerMillisecond = Math.max(0.000001, refillPerMinute / 60_000.0);
        this.distributedConcurrencyLimit = Math.max(1, distributedConcurrencyLimit);
        this.localRateCapacity = Math.max(1, localRateCapacity);
        this.localRefillPerMillisecond = Math.max(0.000001, localRefillPerMinute / 60_000.0);
        this.localConcurrencyLimit = Math.max(1, localConcurrencyLimit);
        this.leaseMilliseconds = Duration.ofSeconds(Math.max(1, leaseSeconds)).toMillis();
        this.localFallbackEnabled = localFallbackEnabled;
        this.clock = clock;
    }

    public void requireRate(String workspaceId, String workload) {
        if (!enabled) {
            count("rate", workload, "disabled", "none");
            return;
        }
        String actorFingerprint = fingerprint(currentUserProvider.requireUserId());
        String key = rateKey(workspaceId, actorFingerprint, workload);
        Decision decision = redisRateDecision(key);
        String backend = "redis";
        if (decision == Decision.UNAVAILABLE) {
            requireLocalFallbackOrFail("rate", workload);
            decision = localRateDecision(key);
            backend = "local";
            count("rate", workload, decision.result(), backend);
        } else {
            count("rate", workload, decision.result(), backend);
        }
        if (decision == Decision.REJECTED) {
            countRejection("rate", workload, backend);
            throw new BusinessException(
                    "WORKLOAD_RATE_LIMITED",
                    "请求过于频繁，请稍后重试",
                    HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    public void acquireLease(String workspaceId, String workload, String leaseToken) {
        if (!enabled) {
            count("concurrency", workload, "disabled", "none");
            return;
        }
        String key = concurrencyKey(workspaceId, workload);
        QuotaDecision outcome = redisLeaseDecision(key, leaseToken, false);
        String backend = "redis";
        if (outcome.decision() == Decision.UNAVAILABLE) {
            requireLocalFallbackOrFail("concurrency", workload);
            outcome = localLeaseDecision(key, leaseToken, false);
            backend = "local";
            count("concurrency", workload, outcome.decision().result(), backend);
        } else {
            count("concurrency", workload, outcome.decision().result(), backend);
        }
        if (outcome.decision() == Decision.GRANTED && "redis".equals(backend)) {
            mirrorLocalLease(key, leaseToken);
        }
        countRecovered(workload, backend, outcome.recoveredLeases());
        if (outcome.decision() == Decision.REJECTED) {
            countRejection("concurrency", workload, backend);
            throw new BusinessException(
                    "WORKLOAD_CONCURRENCY_LIMITED",
                    "当前并发任务已达上限，请稍后重试",
                    HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    public void renewLease(String workspaceId, String workload, String leaseToken) {
        if (!enabled) {
            return;
        }
        String key = concurrencyKey(workspaceId, workload);
        QuotaDecision outcome = redisLeaseDecision(key, leaseToken, true);
        String backend = "redis";
        if (outcome.decision() == Decision.UNAVAILABLE) {
            requireLocalFallbackOrFail("renew", workload);
            outcome = localLeaseDecision(key, leaseToken, true);
            backend = "local";
            count("renew", workload, outcome.decision().result(), backend);
        } else {
            count("renew", workload, outcome.decision().result(), backend);
        }
        if (outcome.decision() == Decision.GRANTED && "redis".equals(backend)) {
            mirrorLocalLease(key, leaseToken);
        }
        countRecovered(workload, backend, outcome.recoveredLeases());
        if (outcome.decision() == Decision.REJECTED) {
            countRejection("renew", workload, backend);
            throw new BusinessException(
                    "WORKLOAD_LEASE_LOST",
                    "任务并发租约已失效",
                    HttpStatus.CONFLICT);
        }
    }

    public void releaseLease(String workspaceId, String workload, String leaseToken) {
        if (!enabled) {
            return;
        }
        String key = concurrencyKey(workspaceId, workload);
        boolean redisReleased = false;
        if (redisTemplate != null) {
            try {
                redisTemplate.execute(LEASE_RELEASE, List.of(key), leaseToken);
                redisReleased = true;
                count("release", workload, "released", "redis");
            } catch (RuntimeException exception) {
                count("release", workload, "error", "redis");
            }
        }
        long now = clock.millis();
        localLeases.computeIfPresent(key, (ignored, currentLeases) -> {
            currentLeases.remove(leaseToken);
            currentLeases.entrySet().removeIf(entry -> entry.getValue() <= now);
            return currentLeases.isEmpty() ? null : currentLeases;
        });
        pruneExpiredLocalLeases(now);
        if (!redisReleased) {
            count("release", workload, "released", "local");
        }
    }

    private Decision redisRateDecision(String key) {
        if (redisTemplate == null) {
            return Decision.UNAVAILABLE;
        }
        try {
            long ttl = Math.max(60_000L,
                    Math.round((rateCapacity / refillPerMillisecond) * 2));
            Long result = redisTemplate.execute(
                    TOKEN_BUCKET,
                    List.of(key),
                    Integer.toString(rateCapacity),
                    Double.toString(refillPerMillisecond),
                    Long.toString(ttl));
            return Long.valueOf(1).equals(result) ? Decision.GRANTED : Decision.REJECTED;
        } catch (RuntimeException exception) {
            count("rate", workloadFromKey(key), "error", "redis");
            return Decision.UNAVAILABLE;
        }
    }

    private void requireLocalFallbackOrFail(String control, String workload) {
        if (localFallbackEnabled) {
            return;
        }
        count(control, workload, "unavailable", "redis");
        throw new BusinessException(
                "WORKLOAD_QUOTA_UNAVAILABLE",
                "配额服务暂不可用，请稍后重试",
                HttpStatus.SERVICE_UNAVAILABLE
        );
    }

    private QuotaDecision redisLeaseDecision(String key, String leaseToken, boolean renew) {
        if (redisTemplate == null) {
            return QuotaDecision.unavailable();
        }
        try {
            Long result = renew
                    ? redisTemplate.execute(
                            LEASE_RENEW,
                            List.of(key),
                            Long.toString(leaseMilliseconds),
                            Long.toString(leaseMilliseconds * 2),
                            leaseToken)
                    : redisTemplate.execute(
                            LEASE_ACQUIRE,
                            List.of(key),
                            Integer.toString(distributedConcurrencyLimit),
                            Long.toString(leaseMilliseconds),
                            Long.toString(leaseMilliseconds * 2),
                            leaseToken);
            if (result == null || result < 0) {
                return QuotaDecision.unavailable();
            }
            return new QuotaDecision(
                    result % 2 == 1 ? Decision.GRANTED : Decision.REJECTED,
                    result / 2
            );
        } catch (RuntimeException exception) {
            count("concurrency", workloadFromKey(key), "error", "redis");
            return QuotaDecision.unavailable();
        }
    }

    private Decision localRateDecision(String key) {
        long now = clock.millis();
        LocalBucket bucket = localBuckets.computeIfAbsent(
                key, ignored -> new LocalBucket(localRateCapacity, now));
        synchronized (bucket) {
            long elapsed = Math.max(0, now - bucket.updatedAt);
            bucket.tokens = Math.min(
                    localRateCapacity,
                    bucket.tokens + elapsed * localRefillPerMillisecond);
            bucket.updatedAt = now;
            if (bucket.tokens < 1) {
                return Decision.REJECTED;
            }
            bucket.tokens -= 1;
            return Decision.GRANTED;
        }
    }

    private QuotaDecision localLeaseDecision(String key, String leaseToken, boolean renew) {
        long now = clock.millis();
        AtomicReference<QuotaDecision> outcome = new AtomicReference<>();
        localLeases.compute(key, (ignored, currentLeases) -> {
            Map<String, Long> leases = currentLeases == null
                    ? new ConcurrentHashMap<>()
                    : currentLeases;
            int previousSize = leases.size();
            leases.entrySet().removeIf(entry -> entry.getValue() <= now);
            long recovered = previousSize - leases.size();
            if (renew && !leases.containsKey(leaseToken)) {
                outcome.set(new QuotaDecision(Decision.REJECTED, recovered));
                return leases.isEmpty() ? null : leases;
            }
            if (!renew && !leases.containsKey(leaseToken) && leases.size() >= localConcurrencyLimit) {
                outcome.set(new QuotaDecision(Decision.REJECTED, recovered));
                return leases;
            }
            leases.put(leaseToken, now + leaseMilliseconds);
            outcome.set(new QuotaDecision(Decision.GRANTED, recovered));
            return leases;
        });
        scheduleLocalLeaseCleanupIfPresent(key);
        pruneExpiredLocalLeases(now);
        return outcome.get();
    }

    private void mirrorLocalLease(String key, String leaseToken) {
        long now = clock.millis();
        localLeases.compute(key, (ignored, currentLeases) -> {
            Map<String, Long> leases = currentLeases == null
                    ? new ConcurrentHashMap<>()
                    : currentLeases;
            leases.entrySet().removeIf(entry -> entry.getValue() <= now);
            leases.put(leaseToken, now + leaseMilliseconds);
            return leases;
        });
        scheduleLocalLeaseCleanupIfPresent(key);
        pruneExpiredLocalLeases(now);
    }

    private void scheduleLocalLeaseCleanupIfPresent(String key) {
        if (localLeases.containsKey(key) && scheduledLocalLeaseKeys.add(key)) {
            localLeaseCleanupQueue.offer(key);
        }
    }

    private void pruneExpiredLocalLeases(long now) {
        List<String> activeKeys = new ArrayList<>(LOCAL_LEASE_CLEANUP_BATCH_SIZE);
        for (int index = 0; index < LOCAL_LEASE_CLEANUP_BATCH_SIZE; index++) {
            String key = localLeaseCleanupQueue.poll();
            if (key == null) {
                break;
            }
            AtomicBoolean active = new AtomicBoolean();
            localLeases.computeIfPresent(key, (ignored, leases) -> {
                leases.entrySet().removeIf(entry -> entry.getValue() <= now);
                if (leases.isEmpty()) {
                    return null;
                }
                active.set(true);
                return leases;
            });
            if (active.get()) {
                activeKeys.add(key);
                continue;
            }
            scheduledLocalLeaseKeys.remove(key);
            scheduleLocalLeaseCleanupIfPresent(key);
        }
        activeKeys.forEach(localLeaseCleanupQueue::offer);
    }

    private String rateKey(String workspaceId, String actorFingerprint, String workload) {
        return keyPrefix("rate") + workspaceId + ":" + actorFingerprint + ":" + normalize(workload);
    }

    private String concurrencyKey(String workspaceId, String workload) {
        return keyPrefix("concurrency") + workspaceId + ":" + normalize(workload);
    }

    String keyPrefix(String control) {
        return KEY_PREFIX + environment + ":" + control + ":";
    }

    private String normalize(String workload) {
        return workload == null ? "unknown" : workload.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String namespace(String value) {
        if (value == null || value.isBlank()) {
            return "local";
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "-");
        return normalized.isBlank() ? "local" : normalized;
    }

    private String workloadFromKey(String key) {
        int separator = key.lastIndexOf(':');
        return separator < 0 ? "unknown" : key.substring(separator + 1);
    }

    private String fingerprint(String value) {
        try {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private void count(String control, String workload, String result, String backend) {
        meterRegistry.counter(
                "noteweave.quota.decisions",
                "control", control,
                "workload", normalize(workload),
                "result", result,
                "backend", backend)
                .increment();
    }

    private void countRejection(String control, String workload, String backend) {
        meterRegistry.counter(
                "noteweave.quota.rejections",
                "control", control,
                "workload", normalize(workload),
                "backend", backend)
                .increment();
    }

    private void countRecovered(String workload, String backend, long recoveredLeases) {
        if (recoveredLeases <= 0) {
            return;
        }
        meterRegistry.counter(
                "noteweave.quota.lease.recovered",
                "workload", normalize(workload),
                "backend", backend)
                .increment(recoveredLeases);
    }

    private record QuotaDecision(Decision decision, long recoveredLeases) {
        private static QuotaDecision unavailable() {
            return new QuotaDecision(Decision.UNAVAILABLE, 0);
        }
    }

    private enum Decision {
        GRANTED,
        REJECTED,
        UNAVAILABLE;

        private String result() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private static final class LocalBucket {
        private double tokens;
        private long updatedAt;

        private LocalBucket(double tokens, long updatedAt) {
            this.tokens = tokens;
            this.updatedAt = updatedAt;
        }
    }
}
