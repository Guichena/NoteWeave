package com.noteweave.research;

import com.noteweave.common.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** MA4C independent, fail-closed multi-worker permit service for Research tools. */
@Service
public class ResearchAgentRateLimitService {
    private static final String SCRIPT = """
            local now = tonumber(ARGV[1]); local capacity = tonumber(ARGV[2]);
            local refill = tonumber(ARGV[3]); local ttl = tonumber(ARGV[4]);
            local values = {}
            for i,key in ipairs(KEYS) do
              local tokens = tonumber(redis.call('HGET', key, 'tokens'))
              local updated = tonumber(redis.call('HGET', key, 'updated_at'))
              if tokens == nil then tokens = capacity; updated = now end
              if now > updated then tokens = math.min(capacity, tokens + (now - updated) * refill); updated = now end
              if tokens < 1 then return 0 end
              values[i] = tokens - 1
            end
            for i,key in ipairs(KEYS) do
              redis.call('HSET', key, 'tokens', values[i], 'updated_at', now)
              redis.call('PEXPIRE', key, ttl)
            end
            return 1
            """;
    private static final DefaultRedisScript<Long> PERMIT = new DefaultRedisScript<>(SCRIPT, Long.class);

    private final StringRedisTemplate redis;
    private final MeterRegistry meters;
    private final boolean enabled;
    private final boolean allowLocalFallback;
    private final int capacity;
    private final double refillPerMs;
    private final Clock clock;

    @Autowired
    public ResearchAgentRateLimitService(ObjectProvider<StringRedisTemplate> redis, MeterRegistry meters,
                                         @Value("${noteweave.research.agent.rate-limit.enabled:true}") boolean enabled,
                                         @Value("${noteweave.research.agent.rate-limit.allow-local-fallback:false}") boolean allowLocalFallback,
                                         @Value("${noteweave.research.agent.rate-limit.capacity:10}") int capacity,
                                         @Value("${noteweave.research.agent.rate-limit.refill-per-minute:10}") double refillPerMinute) {
        this(redis.getIfAvailable(), meters, enabled, allowLocalFallback, capacity, refillPerMinute, Clock.systemUTC());
    }

    ResearchAgentRateLimitService(StringRedisTemplate redis, MeterRegistry meters, boolean enabled, boolean allowLocalFallback,
                                  int capacity, double refillPerMinute) {
        this(redis, meters, enabled, allowLocalFallback, capacity, refillPerMinute, Clock.systemUTC());
    }

    private ResearchAgentRateLimitService(StringRedisTemplate redis, MeterRegistry meters, boolean enabled, boolean allowLocalFallback,
                                          int capacity, double refillPerMinute, Clock clock) {
        this.redis = redis; this.meters = meters; this.enabled = enabled; this.allowLocalFallback = allowLocalFallback;
        this.capacity = Math.max(1, capacity); this.refillPerMs = Math.max(0.000001, refillPerMinute / 60_000.0); this.clock = clock;
    }

    public void requirePermit(PermitRequest request) {
        validate(request);
        if (!enabled) return;
        if (redis == null) { unavailable(request); return; }
        try {
            long ttl = Math.max(60_000L, Math.round(capacity / refillPerMs * 2));
            Long result = redis.execute(PERMIT, keys(request), Long.toString(clock.millis()), Integer.toString(capacity),
                    Double.toString(refillPerMs), Long.toString(ttl));
            if (Long.valueOf(1).equals(result)) { meters.counter("noteweave.research.agent.permit", "result", "granted").increment(); return; }
            if (Long.valueOf(0).equals(result)) {
                meters.counter("noteweave.research.agent.permit", "result", "limited").increment();
                throw new BusinessException("RESEARCH_AGENT_RATE_LIMITED", "Research provider rate limit reached", HttpStatus.TOO_MANY_REQUESTS);
            }
            unavailable(request);
        } catch (BusinessException exception) { throw exception;
        } catch (RuntimeException exception) { unavailable(request); }
    }

    private void unavailable(PermitRequest request) {
        meters.counter("noteweave.research.agent.permit", "result", allowLocalFallback ? "local_development" : "unavailable").increment();
        if (allowLocalFallback) return; // Explicit development-only escape hatch; never default.
        throw new BusinessException("RESEARCH_AGENT_RATE_LIMIT_UNAVAILABLE", "Research rate limiter is unavailable", HttpStatus.SERVICE_UNAVAILABLE);
    }
    private List<String> keys(PermitRequest r) { return List.of("noteweave:v1:research:provider:" + r.providerKey(), "noteweave:v1:research:workspace:" + r.workspaceId(), "noteweave:v1:research:run:" + r.runId() + ":" + r.role()); }
    private void validate(PermitRequest r) { if (r == null || blank(r.providerKey()) || blank(r.workspaceId()) || blank(r.runId()) || blank(r.role())) throw new BusinessException("RESEARCH_AGENT_RATE_LIMIT_INVALID", "Research rate-limit request is invalid"); }
    private boolean blank(String v) { return v == null || v.isBlank(); }
    public record PermitRequest(String providerKey, String workspaceId, String runId, String role) { }
}
