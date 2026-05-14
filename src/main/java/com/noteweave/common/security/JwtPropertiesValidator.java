package com.noteweave.common.security;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JwtPropertiesValidator {

    private static final Set<String> STRICT_PROFILES = Set.of("prod", "staging");
    private static final int MIN_SECRET_LENGTH = 32;

    private final JwtProperties jwtProperties;
    private final Environment environment;

    @PostConstruct
    public void validate() {
        if (!isStrictProfile()) {
            return;
        }

        String secret = jwtProperties.secretKey();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT secret key must not be blank in prod/staging profile");
        }

        String normalized = secret.trim();
        if (normalized.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("JWT secret key is too short in prod/staging profile");
        }
        if (isDefaultLike(normalized)) {
            throw new IllegalStateException("Default JWT secret key is not allowed in prod/staging profile");
        }
    }

    private boolean isStrictProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .anyMatch(STRICT_PROFILES::contains);
    }

    private boolean isDefaultLike(String secret) {
        String normalized = secret.toLowerCase(Locale.ROOT);
        return normalized.startsWith("change-me-in-dev");
    }
}
