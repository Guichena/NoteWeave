package com.noteweave.security;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuthLoginRateLimiter {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final boolean enabled;
    private final Duration window;
    private final int ipMaxFailures;
    private final int loginMaxFailures;
    private final int backoffStartsAt;
    private final long maxBackoffSeconds;

    @Autowired
    public AuthLoginRateLimiter(
            JdbcTemplate jdbcTemplate,
            @Value("${noteweave.security.login-rate-limit.enabled:true}") boolean enabled,
            @Value("${noteweave.security.login-rate-limit.window-minutes:15}") long windowMinutes,
            @Value("${noteweave.security.login-rate-limit.ip-max-failures:20}") int ipMaxFailures,
            @Value("${noteweave.security.login-rate-limit.login-max-failures:8}") int loginMaxFailures,
            @Value("${noteweave.security.login-rate-limit.backoff-starts-at:3}") int backoffStartsAt,
            @Value("${noteweave.security.login-rate-limit.max-backoff-seconds:60}") long maxBackoffSeconds
    ) {
        this(jdbcTemplate, Clock.systemUTC(), enabled, windowMinutes, ipMaxFailures,
                loginMaxFailures, backoffStartsAt, maxBackoffSeconds);
    }

    AuthLoginRateLimiter(
            JdbcTemplate jdbcTemplate,
            Clock clock,
            boolean enabled,
            long windowMinutes,
            int ipMaxFailures,
            int loginMaxFailures,
            int backoffStartsAt,
            long maxBackoffSeconds
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.enabled = enabled;
        this.window = Duration.ofMinutes(Math.max(1, windowMinutes));
        this.ipMaxFailures = Math.max(1, ipMaxFailures);
        this.loginMaxFailures = Math.max(1, loginMaxFailures);
        this.backoffStartsAt = Math.max(1, backoffStartsAt);
        this.maxBackoffSeconds = Math.max(1, maxBackoffSeconds);
    }

    AttemptContext beforeAttempt(String clientAddress, String login) {
        String normalizedIp = normalize(clientAddress == null || clientAddress.isBlank()
                ? "unknown" : clientAddress);
        String normalizedLogin = normalize(login);
        String ipHash = TokenHasher.sha256(normalizedIp);
        String loginHash = TokenHasher.sha256(normalizedLogin);
        AttemptContext context = new AttemptContext(
                TokenHasher.sha256("IP\n" + normalizedIp),
                TokenHasher.sha256("LOGIN\n" + normalizedLogin),
                ipHash,
                loginHash
        );
        if (!enabled) return context;

        ensureDimension(context.ipDimensionKey(), "IP");
        ensureDimension(context.loginDimensionKey(), "LOGIN");
        Instant now = clock.instant();
        ThrottleState ip = lockAndRefresh(context.ipDimensionKey(), now);
        ThrottleState account = lockAndRefresh(context.loginDimensionKey(), now);
        if (blocked(ip, ipMaxFailures, now) || blocked(account, loginMaxFailures, now)) {
            audit(context, "RATE_LIMITED", Math.max(ip.failureCount(), account.failureCount()), now);
            throw new BusinessException(
                    "AUTH_LOGIN_RATE_LIMITED",
                    "登录尝试过于频繁，请稍后重试",
                    HttpStatus.TOO_MANY_REQUESTS
            );
        }
        return context;
    }

    void recordFailure(AttemptContext context) {
        if (!enabled) return;
        Instant now = clock.instant();
        int ipFailures = incrementFailure(context.ipDimensionKey(), ipMaxFailures, now);
        int loginFailures = incrementFailure(context.loginDimensionKey(), loginMaxFailures, now);
        audit(context, "FAILED", Math.max(ipFailures, loginFailures), now);
    }

    void recordSuccess(AttemptContext context) {
        if (!enabled) return;
        Instant now = clock.instant();
        jdbcTemplate.update("""
                update auth_login_throttle
                set failure_count = 0, window_started_at = ?, blocked_until = null, updated_at = ?
                where dimension_key = ?
                """, Timestamp.from(now), Timestamp.from(now), context.loginDimensionKey());
        audit(context, "SUCCEEDED", 0, now);
    }

    private void ensureDimension(String key, String type) {
        jdbcTemplate.update("""
                insert into auth_login_throttle(
                    dimension_key, dimension_type, failure_count, window_started_at, updated_at
                ) values (?, ?, 0, current_timestamp, current_timestamp)
                on duplicate key update dimension_key = dimension_key
                """, key, type);
    }

    private ThrottleState lockAndRefresh(String key, Instant now) {
        ThrottleState state = jdbcTemplate.query("""
                select failure_count, window_started_at, blocked_until
                from auth_login_throttle where dimension_key = ? for update
                """, rs -> rs.next() ? new ThrottleState(
                rs.getInt("failure_count"),
                rs.getTimestamp("window_started_at").toInstant(),
                rs.getTimestamp("blocked_until") == null
                        ? null : rs.getTimestamp("blocked_until").toInstant()) : null, key);
        if (state == null) throw new IllegalStateException("Login throttle row is missing");
        if (state.windowStartedAt().plus(window).isAfter(now)) return state;
        jdbcTemplate.update("""
                update auth_login_throttle
                set failure_count = 0, window_started_at = ?, blocked_until = null, updated_at = ?
                where dimension_key = ?
                """, Timestamp.from(now), Timestamp.from(now), key);
        return new ThrottleState(0, now, null);
    }

    private boolean blocked(ThrottleState state, int maximumFailures, Instant now) {
        return state.failureCount() >= maximumFailures
                || state.blockedUntil() != null && state.blockedUntil().isAfter(now);
    }

    private int incrementFailure(String key, int maximumFailures, Instant now) {
        ThrottleState state = lockAndRefresh(key, now);
        int next = state.failureCount() + 1;
        Instant blockedUntil = null;
        if (next >= maximumFailures) {
            blockedUntil = state.windowStartedAt().plus(window);
        } else if (next >= backoffStartsAt) {
            int exponent = Math.min(20, next - backoffStartsAt);
            long delay = Math.min(maxBackoffSeconds, 1L << exponent);
            blockedUntil = now.plusSeconds(delay);
        }
        jdbcTemplate.update("""
                update auth_login_throttle
                set failure_count = ?, blocked_until = ?, updated_at = ?
                where dimension_key = ?
                """, next, blockedUntil == null ? null : Timestamp.from(blockedUntil),
                Timestamp.from(now), key);
        return next;
    }

    private void audit(AttemptContext context, String outcome, int failureCount, Instant now) {
        jdbcTemplate.update("""
                insert into auth_login_security_event(
                    id, ip_hash, login_hash, outcome, failure_count, occurred_at
                ) values (?, ?, ?, ?, ?, ?)
                """, Ids.newId(), context.ipHash(), context.loginHash(), outcome,
                failureCount, Timestamp.from(now));
    }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }

    record AttemptContext(
            String ipDimensionKey,
            String loginDimensionKey,
            String ipHash,
            String loginHash
    ) { }

    private record ThrottleState(
            int failureCount,
            Instant windowStartedAt,
            Instant blockedUntil
    ) { }
}
