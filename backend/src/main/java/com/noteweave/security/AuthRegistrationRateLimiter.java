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
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthRegistrationRateLimiter {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final boolean enabled;
    private final Duration window;
    private final int ipMaxAttempts;
    private final int accountMaxAttempts;

    @Autowired
    public AuthRegistrationRateLimiter(
            JdbcTemplate jdbcTemplate,
            @Value("${noteweave.security.registration-rate-limit.enabled:true}") boolean enabled,
            @Value("${noteweave.security.registration-rate-limit.window-minutes:60}") long windowMinutes,
            @Value("${noteweave.security.registration-rate-limit.ip-max-attempts:20}") int ipMaxAttempts,
            @Value("${noteweave.security.registration-rate-limit.account-max-attempts:5}") int accountMaxAttempts
    ) {
        this(jdbcTemplate, Clock.systemUTC(), enabled, windowMinutes, ipMaxAttempts, accountMaxAttempts);
    }

    AuthRegistrationRateLimiter(
            JdbcTemplate jdbcTemplate,
            Clock clock,
            boolean enabled,
            long windowMinutes,
            int ipMaxAttempts,
            int accountMaxAttempts
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.enabled = enabled;
        this.window = Duration.ofMinutes(Math.max(1, windowMinutes));
        this.ipMaxAttempts = Math.max(1, ipMaxAttempts);
        this.accountMaxAttempts = Math.max(1, accountMaxAttempts);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public void beforeAttempt(String clientAddress, String username, String email) {
        if (!enabled) {
            return;
        }
        String normalizedIp = normalize(clientAddress == null || clientAddress.isBlank()
                ? "unknown" : clientAddress);
        String normalizedUsername = normalize(username);
        String normalizedEmail = normalize(email);
        AttemptContext context = new AttemptContext(
                dimensionKey("REGISTER_IP", normalizedIp),
                dimensionKey("REGISTER_USERNAME", normalizedUsername),
                dimensionKey("REGISTER_EMAIL", normalizedEmail),
                TokenHasher.sha256(normalizedIp),
                TokenHasher.sha256(normalizedUsername),
                TokenHasher.sha256(normalizedEmail)
        );
        ensureDimension(context.ipDimensionKey(), "IP");
        ensureDimension(context.usernameDimensionKey(), "USERNAME");
        ensureDimension(context.emailDimensionKey(), "EMAIL");
        Instant now = clock.instant();
        AttemptState ip = lockAndRefresh(context.ipDimensionKey(), now);
        AttemptState usernameState = lockAndRefresh(context.usernameDimensionKey(), now);
        AttemptState emailState = lockAndRefresh(context.emailDimensionKey(), now);
        int highest = Math.max(ip.attemptCount(), Math.max(
                usernameState.attemptCount(), emailState.attemptCount()));
        if (ip.attemptCount() >= ipMaxAttempts
                || usernameState.attemptCount() >= accountMaxAttempts
                || emailState.attemptCount() >= accountMaxAttempts) {
            audit(context, "RATE_LIMITED", highest, now);
            throw new BusinessException(
                    "AUTH_REGISTRATION_RATE_LIMITED",
                    "注册尝试过于频繁，请稍后重试",
                    HttpStatus.TOO_MANY_REQUESTS
            );
        }
        increment(context.ipDimensionKey(), now);
        increment(context.usernameDimensionKey(), now);
        increment(context.emailDimensionKey(), now);
        audit(context, "ATTEMPTED", highest + 1, now);
    }

    private void ensureDimension(String key, String type) {
        jdbcTemplate.update("""
                insert into auth_registration_throttle(
                    dimension_key, dimension_type, attempt_count, window_started_at, updated_at
                ) values (?, ?, 0, current_timestamp, current_timestamp)
                on duplicate key update dimension_key = dimension_key
                """, key, type);
    }

    private AttemptState lockAndRefresh(String key, Instant now) {
        AttemptState state = jdbcTemplate.query("""
                select attempt_count, window_started_at
                from auth_registration_throttle where dimension_key = ? for update
                """, rs -> rs.next() ? new AttemptState(
                rs.getInt("attempt_count"), rs.getTimestamp("window_started_at").toInstant()) : null, key);
        if (state == null) {
            throw new IllegalStateException("Registration throttle row is missing");
        }
        if (state.windowStartedAt().plus(window).isAfter(now)) {
            return state;
        }
        jdbcTemplate.update("""
                update auth_registration_throttle
                set attempt_count = 0, window_started_at = ?, updated_at = ?
                where dimension_key = ?
                """, Timestamp.from(now), Timestamp.from(now), key);
        return new AttemptState(0, now);
    }

    private void increment(String key, Instant now) {
        jdbcTemplate.update("""
                update auth_registration_throttle
                set attempt_count = attempt_count + 1, updated_at = ?
                where dimension_key = ?
                """, Timestamp.from(now), key);
    }

    private void audit(AttemptContext context, String outcome, int attemptCount, Instant now) {
        jdbcTemplate.update("""
                insert into auth_registration_security_event(
                    id, ip_hash, username_hash, email_hash, outcome, attempt_count, occurred_at
                ) values (?, ?, ?, ?, ?, ?, ?)
                """, Ids.newId(), context.ipHash(), context.usernameHash(), context.emailHash(),
                outcome, attemptCount, Timestamp.from(now));
    }

    private String dimensionKey(String type, String value) {
        return TokenHasher.sha256(type + "\n" + value);
    }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }

    private record AttemptContext(
            String ipDimensionKey,
            String usernameDimensionKey,
            String emailDimensionKey,
            String ipHash,
            String usernameHash,
            String emailHash
    ) { }

    private record AttemptState(int attemptCount, Instant windowStartedAt) { }
}
