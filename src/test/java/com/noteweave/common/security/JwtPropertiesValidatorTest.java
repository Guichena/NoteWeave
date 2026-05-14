package com.noteweave.common.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class JwtPropertiesValidatorTest {

    @Test
    void devProfile_ShouldAllowDefaultSecret() {
        JwtProperties properties = new JwtProperties("change-me-in-dev", 900, 1209600);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        JwtPropertiesValidator validator = new JwtPropertiesValidator(properties, environment);
        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void prodProfile_ShouldRejectDefaultSecret() {
        JwtProperties properties = new JwtProperties("change-me-in-dev-change-me-in-dev", 900, 1209600);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        JwtPropertiesValidator validator = new JwtPropertiesValidator(properties, environment);
        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Default JWT secret key");
    }

    @Test
    void stagingProfile_ShouldRejectShortSecret() {
        JwtProperties properties = new JwtProperties("short-secret", 900, 1209600);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("staging");

        JwtPropertiesValidator validator = new JwtPropertiesValidator(properties, environment);
        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too short");
    }
}
