package com.noteweave.infra;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

class RedisConfigTest {

    @Test
    void connectionFactoryShouldApplyConfiguredPassword() {
        LettuceConnectionFactory factory = (LettuceConnectionFactory) new RedisConfig()
                .redisConnectionFactory("redis", 6379, "demo-secret", 500, 750);

        assertThat(factory.getStandaloneConfiguration().getPassword().get())
                .isEqualTo("demo-secret".toCharArray());
    }

    @Test
    void connectionFactoryShouldAllowPasswordlessRedis() {
        LettuceConnectionFactory factory = (LettuceConnectionFactory) new RedisConfig()
                .redisConnectionFactory("redis", 6379, "", 500, 750);

        assertThat(factory.getStandaloneConfiguration().getPassword().isPresent()).isFalse();
    }
}
