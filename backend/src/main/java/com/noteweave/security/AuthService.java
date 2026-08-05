package com.noteweave.security;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordHasher passwordHasher;
    private final AuthLoginRateLimiter loginRateLimiter;
    private final String invalidUserPasswordHash;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Clock clock;
    private final long accessTokenMinutes;
    private final long refreshTokenDays;

    @Autowired
    public AuthService(
            JdbcTemplate jdbcTemplate,
            PasswordHasher passwordHasher,
            AuthLoginRateLimiter loginRateLimiter,
            @Value("${noteweave.security.access-token-minutes:15}") long accessTokenMinutes,
            @Value("${noteweave.security.refresh-token-days:30}") long refreshTokenDays
    ) {
        this(jdbcTemplate, passwordHasher, loginRateLimiter, Clock.systemUTC(),
                accessTokenMinutes, refreshTokenDays);
    }

    AuthService(JdbcTemplate jdbcTemplate, PasswordHasher passwordHasher,
                AuthLoginRateLimiter loginRateLimiter, Clock clock,
                long accessTokenMinutes, long refreshTokenDays) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordHasher = passwordHasher;
        this.loginRateLimiter = loginRateLimiter;
        this.invalidUserPasswordHash = passwordHasher.hash("noteweave-invalid-user-timing-equalizer");
        this.clock = clock;
        this.accessTokenMinutes = accessTokenMinutes;
        this.refreshTokenDays = refreshTokenDays;
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public AuthSessionResponse login(String login, String password, String clientAddress) {
        AuthLoginRateLimiter.AttemptContext attempt = loginRateLimiter.beforeAttempt(clientAddress, login);
        UserCredential user = findUser(login);
        boolean passwordMatches = passwordHasher.matches(
                password, user == null ? invalidUserPasswordHash : user.passwordHash());
        if (user == null || !passwordMatches) {
            loginRateLimiter.recordFailure(attempt);
            throw new BusinessException("AUTHENTICATION_INVALID", "用户名或密码错误", HttpStatus.UNAUTHORIZED);
        }
        loginRateLimiter.recordSuccess(attempt);
        Instant now = clock.instant();
        String accessToken = newToken();
        String refreshToken = newToken();
        Instant accessExpiresAt = now.plus(accessTokenMinutes, ChronoUnit.MINUTES);
        Instant refreshExpiresAt = now.plus(refreshTokenDays, ChronoUnit.DAYS);
        jdbcTemplate.update("""
                insert into user_session(
                    id, user_id, session_token, token_hash, refresh_token_hash, status,
                    created_at, expires_at, refresh_expires_at, last_used_at
                ) values (?, ?, '', ?, ?, 'ACTIVE', ?, ?, ?, ?)
                """, Ids.newId(), user.userId(), TokenHasher.sha256(accessToken),
                TokenHasher.sha256(refreshToken), Timestamp.from(now), Timestamp.from(accessExpiresAt),
                Timestamp.from(refreshExpiresAt), Timestamp.from(now));
        return response(user, accessToken, refreshToken, accessExpiresAt, refreshExpiresAt);
    }

    @Transactional
    public AuthSessionResponse refresh(String refreshToken) {
        String hash = TokenHasher.sha256(refreshToken);
        SessionUser session = jdbcTemplate.query("""
                select s.id, u.id as user_id, u.username, u.email, u.display_name, u.password_hash
                from user_session s
                join users u on u.id = s.user_id
                where s.refresh_token_hash = ? and s.status = 'ACTIVE' and s.revoked_at is null
                  and s.refresh_expires_at > current_timestamp and u.status = 'ACTIVE'
                """, rs -> rs.next() ? new SessionUser(
                rs.getString("id"), rs.getString("user_id"), rs.getString("username"),
                rs.getString("email"), rs.getString("display_name"), rs.getString("password_hash")) : null, hash);
        if (session == null) {
            throw new BusinessException("REFRESH_TOKEN_INVALID", "刷新令牌无效或已过期", HttpStatus.UNAUTHORIZED);
        }
        Instant now = clock.instant();
        String nextAccessToken = newToken();
        String nextRefreshToken = newToken();
        Instant accessExpiresAt = now.plus(accessTokenMinutes, ChronoUnit.MINUTES);
        Instant refreshExpiresAt = now.plus(refreshTokenDays, ChronoUnit.DAYS);
        int updated = jdbcTemplate.update("""
                update user_session
                set token_hash = ?, refresh_token_hash = ?, expires_at = ?, refresh_expires_at = ?, last_used_at = ?
                where id = ? and refresh_token_hash = ? and status = 'ACTIVE' and revoked_at is null
                """, TokenHasher.sha256(nextAccessToken), TokenHasher.sha256(nextRefreshToken),
                Timestamp.from(accessExpiresAt), Timestamp.from(refreshExpiresAt), Timestamp.from(now),
                session.sessionId(), hash);
        if (updated != 1) {
            throw new BusinessException("REFRESH_TOKEN_REPLAYED", "刷新令牌已被轮换", HttpStatus.UNAUTHORIZED);
        }
        return response(session.asCredential(), nextAccessToken, nextRefreshToken, accessExpiresAt, refreshExpiresAt);
    }

    @Transactional
    public void logout(String accessToken) {
        jdbcTemplate.update("""
                update user_session
                set status = 'REVOKED', revoked_at = current_timestamp,
                    token_hash = null, refresh_token_hash = null
                where token_hash = ? and status = 'ACTIVE'
                """, TokenHasher.sha256(accessToken));
    }

    public AuthUserResponse getUser(String userId) {
        List<AuthUserResponse> users = jdbcTemplate.query("""
                select id, username, email, display_name from users
                where id = ? and status = 'ACTIVE'
                """, (rs, rowNum) -> new AuthUserResponse(
                rs.getString("id"), rs.getString("username"), rs.getString("email"), rs.getString("display_name")), userId);
        if (users.isEmpty()) {
            throw new BusinessException("USER_NOT_FOUND", "用户不存在", HttpStatus.NOT_FOUND);
        }
        return users.get(0);
    }

    private UserCredential findUser(String login) {
        return jdbcTemplate.query("""
                select id, username, email, display_name, password_hash
                from users
                where (username = ? or email = ?) and status = 'ACTIVE'
                """, rs -> rs.next() ? new UserCredential(
                rs.getString("id"), rs.getString("username"), rs.getString("email"),
                rs.getString("display_name"), rs.getString("password_hash")) : null, login, login);
    }

    private AuthSessionResponse response(UserCredential user, String accessToken, String refreshToken,
                                         Instant accessExpiresAt, Instant refreshExpiresAt) {
        return new AuthSessionResponse(accessToken, refreshToken, accessExpiresAt.toString(),
                refreshExpiresAt.toString(), new AuthUserResponse(
                user.userId(), user.username(), user.email(), user.displayName()));
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private record UserCredential(String userId, String username, String email, String displayName,
                                  String passwordHash) {
    }

    private record SessionUser(String sessionId, String userId, String username, String email,
                               String displayName, String passwordHash) {
        UserCredential asCredential() {
            return new UserCredential(userId, username, email, displayName, passwordHash);
        }
    }
}
