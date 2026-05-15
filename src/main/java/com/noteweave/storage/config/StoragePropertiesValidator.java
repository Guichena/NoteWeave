package com.noteweave.storage.config;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StoragePropertiesValidator {

    private static final Set<String> STRICT_PROFILES = Set.of("prod", "staging");

    private final StorageProperties storageProperties;
    private final Environment environment;

    @PostConstruct
    public void validate() {
        if (!isStrictProfile()) {
            return;
        }

        String accessKey = trim(storageProperties.minio().accessKey());
        String secretKey = trim(storageProperties.minio().secretKey());
        if (accessKey == null || secretKey == null) {
            throw new IllegalStateException("MinIO credentials must not be blank in prod/staging profile");
        }
        if (isDefaultAccessKey(accessKey) || isDefaultSecretKey(secretKey)) {
            throw new IllegalStateException("Default MinIO credentials are not allowed in prod/staging profile");
        }
    }

    private boolean isStrictProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .anyMatch(STRICT_PROFILES::contains);
    }

    private String trim(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private boolean isDefaultAccessKey(String accessKey) {
        return "noteweave".equals(accessKey.toLowerCase(Locale.ROOT));
    }

    private boolean isDefaultSecretKey(String secretKey) {
        return "noteweave-minio-secret".equals(secretKey.toLowerCase(Locale.ROOT));
    }
}
