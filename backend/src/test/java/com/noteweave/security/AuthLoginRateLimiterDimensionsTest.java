package com.noteweave.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * SEC-02：登录限流是 IP + 账号双维度，成功登录只能重置账号维度，
 * IP 维度的滑动窗口必须保留，否则攻击者每猜中一次就能把 IP 计数清零。
 */
class AuthLoginRateLimiterDimensionsTest {

    private static final String CLIENT_IP = "203.0.113.7";

    private JdbcTemplate jdbc;
    private AuthLoginRateLimiter limiter;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:login-throttle-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                create table auth_login_throttle(
                    dimension_key char(64) primary key,
                    dimension_type varchar(16) not null,
                    failure_count int not null default 0,
                    window_started_at timestamp not null default current_timestamp,
                    blocked_until timestamp null,
                    updated_at timestamp not null default current_timestamp
                )
                """);
        jdbc.execute("""
                create table auth_login_security_event(
                    id varchar(36) primary key,
                    ip_hash char(64) not null,
                    login_hash char(64) not null,
                    outcome varchar(16) not null,
                    failure_count int not null,
                    occurred_at timestamp not null
                )
                """);
        limiter = new AuthLoginRateLimiter(
                jdbc,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
                true,
                15,
                3,
                100,
                100,
                60);
    }

    @Test
    void successfulLoginOnlyResetsTheAccountDimension() {
        limiter.recordFailure(limiter.beforeAttempt(CLIENT_IP, "alice"));
        limiter.recordFailure(limiter.beforeAttempt(CLIENT_IP, "alice"));
        assertThat(failures(ipKey(CLIENT_IP))).isEqualTo(2);

        AuthLoginRateLimiter.AttemptContext success = limiter.beforeAttempt(CLIENT_IP, "alice");
        limiter.recordSuccess(success);

        assertThat(failures(loginKey("alice"))).isZero();
        assertThat(failures(ipKey(CLIENT_IP)))
                .as("成功登录不能清空 IP 维度的失败窗口")
                .isEqualTo(2);
    }

    @Test
    void ipWindowSurvivesSuccessAndStillBlocksOtherAccounts() {
        limiter.recordFailure(limiter.beforeAttempt(CLIENT_IP, "alice"));
        limiter.recordFailure(limiter.beforeAttempt(CLIENT_IP, "alice"));
        limiter.recordSuccess(limiter.beforeAttempt(CLIENT_IP, "alice"));
        limiter.recordFailure(limiter.beforeAttempt(CLIENT_IP, "alice"));

        assertThatThrownBy(() -> limiter.beforeAttempt(CLIENT_IP, "bob"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("登录尝试过于频繁");
        assertThat(failures(loginKey("bob")))
                .as("被 IP 维度拦下时不应给其他账号记失败")
                .isZero();
    }

    @Test
    void differentClientsUseIndependentIpBuckets() {
        limiter.recordFailure(limiter.beforeAttempt("198.51.100.1", "alice"));
        limiter.recordFailure(limiter.beforeAttempt("198.51.100.1", "alice"));
        limiter.recordFailure(limiter.beforeAttempt("198.51.100.1", "alice"));

        limiter.beforeAttempt("198.51.100.2", "alice");
    }

    private int failures(String dimensionKey) {
        return jdbc.queryForObject(
                "select failure_count from auth_login_throttle where dimension_key = ?",
                Integer.class, dimensionKey);
    }

    private static String ipKey(String clientIp) {
        return TokenHasher.sha256("IP\n" + clientIp);
    }

    private static String loginKey(String login) {
        return TokenHasher.sha256("LOGIN\n" + login);
    }
}
