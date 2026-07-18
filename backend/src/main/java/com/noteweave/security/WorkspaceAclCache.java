package com.noteweave.security;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceAclCache {

    static final String KEY_PREFIX = "noteweave:v1:cache:acl:";
    private static final String ROLE_PREFIX = "ROLE:";
    private static final String DENIED_VALUE = "DENIED";

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    private final Duration positiveTtl;
    private final Duration negativeTtl;
    private final long jitterSeconds;

    public WorkspaceAclCache(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            MeterRegistry meterRegistry,
            @Value("${noteweave.security.acl-cache-enabled:true}") boolean enabled,
            @Value("${noteweave.security.acl-cache-ttl-seconds:300}") long positiveTtlSeconds,
            @Value("${noteweave.security.acl-negative-cache-ttl-seconds:30}") long negativeTtlSeconds,
            @Value("${noteweave.security.acl-cache-jitter-seconds:60}") long jitterSeconds
    ) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.meterRegistry = meterRegistry;
        this.enabled = enabled && this.redisTemplate != null;
        this.positiveTtl = Duration.ofSeconds(Math.max(1, positiveTtlSeconds));
        this.negativeTtl = Duration.ofSeconds(Math.max(1, negativeTtlSeconds));
        this.jitterSeconds = Math.max(0, jitterSeconds);
    }

    public Lookup get(String workspaceId, String userId, long aclVersion) {
        if (!enabled) {
            count("disabled");
            return Lookup.miss();
        }
        try {
            String value = redisTemplate.opsForValue().get(key(workspaceId, userId, aclVersion));
            if (value == null) {
                count("miss");
                return Lookup.miss();
            }
            if (DENIED_VALUE.equals(value)) {
                count("negative_hit");
                return Lookup.negative();
            }
            String roleValue = value.startsWith(ROLE_PREFIX)
                    ? value.substring(ROLE_PREFIX.length())
                    : value;
            count("hit");
            return Lookup.granted(WorkspaceRole.valueOf(roleValue));
        } catch (IllegalArgumentException exception) {
            count("invalid");
            return Lookup.miss();
        } catch (RuntimeException exception) {
            count("error");
            return Lookup.miss();
        }
    }

    public void put(String workspaceId, String userId, long aclVersion, WorkspaceRole role) {
        put(workspaceId, userId, aclVersion, ROLE_PREFIX + role.name(), positiveTtl, "load");
    }

    public void putDenied(String workspaceId, String userId, long aclVersion) {
        put(workspaceId, userId, aclVersion, DENIED_VALUE, negativeTtl, "negative_load");
    }

    private void put(
            String workspaceId,
            String userId,
            long aclVersion,
            String value,
            Duration ttl,
            String result
    ) {
        if (!enabled) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    key(workspaceId, userId, aclVersion), value, withJitter(ttl));
            count(result);
        } catch (RuntimeException exception) {
            count("error");
        }
    }

    private String key(String workspaceId, String userId, long aclVersion) {
        return KEY_PREFIX + workspaceId + ":" + userId + ":" + aclVersion;
    }

    private Duration withJitter(Duration base) {
        if (jitterSeconds == 0) {
            return base;
        }
        return base.plusSeconds(ThreadLocalRandom.current().nextLong(jitterSeconds + 1));
    }

    private void count(String result) {
        meterRegistry.counter("noteweave.security.acl.cache", "result", result).increment();
        meterRegistry.counter(
                "noteweave.cache.requests", "cache", "workspace_acl", "result", result)
                .increment();
    }

    public record Lookup(Optional<WorkspaceRole> role, boolean denied) {

        public Lookup {
            role = role == null ? Optional.empty() : role;
            if (denied && role.isPresent()) {
                throw new IllegalArgumentException("Denied ACL cache lookup cannot contain a role");
            }
        }

        static Lookup miss() {
            return new Lookup(Optional.empty(), false);
        }

        static Lookup negative() {
            return new Lookup(Optional.empty(), true);
        }

        static Lookup granted(WorkspaceRole role) {
            return new Lookup(Optional.of(role), false);
        }
    }
}
