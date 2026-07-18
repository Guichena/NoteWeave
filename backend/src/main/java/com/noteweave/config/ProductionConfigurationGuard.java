package com.noteweave.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
public class ProductionConfigurationGuard {

    private final String internalAuthToken;
    private final String datasourcePassword;
    private final String minioSecretKey;
    private final boolean localUserFallback;

    public ProductionConfigurationGuard(
            @Value("${noteweave.internal.auth-token:}") String internalAuthToken,
            @Value("${spring.datasource.password:}") String datasourcePassword,
            @Value("${noteweave.storage.minio.secret-key:}") String minioSecretKey,
            @Value("${noteweave.security.local-user-fallback:true}") boolean localUserFallback
    ) {
        this.internalAuthToken = internalAuthToken;
        this.datasourcePassword = datasourcePassword;
        this.minioSecretKey = minioSecretKey;
        this.localUserFallback = localUserFallback;
    }

    @PostConstruct
    void validateProductionConfiguration() {
        validateValues(internalAuthToken, datasourcePassword, minioSecretKey, localUserFallback);
    }

    public static void validateValues(String internalAuthToken, String datasourcePassword, String minioSecretKey) {
        validateValues(internalAuthToken, datasourcePassword, minioSecretKey, false);
    }

    public static void validateValues(
            String internalAuthToken,
            String datasourcePassword,
            String minioSecretKey,
            boolean localUserFallback
    ) {
        if (isWeak(internalAuthToken, "noteweave-internal-dev", "replace-with-a-long-random-development-token")) {
            throw new IllegalStateException("Production requires a non-default NOTEWEAVE_INTERNAL_AUTH_TOKEN");
        }
        if (isWeak(datasourcePassword, "noteweave123", "password", "root")) {
            throw new IllegalStateException("Production requires a non-default datasource password");
        }
        if (isWeak(minioSecretKey, "minioadmin", "replace-with-a-local-development-password")) {
            throw new IllegalStateException("Production requires a non-default MinIO secret key");
        }
        if (localUserFallback) {
            throw new IllegalStateException("Production must disable NOTEWEAVE_LOCAL_USER_FALLBACK");
        }
    }

    private static boolean isWeak(String value, String... forbidden) {
        if (value == null || value.isBlank()) {
            return true;
        }
        for (String candidate : forbidden) {
            if (candidate.equals(value.trim())) {
                return true;
            }
        }
        return false;
    }
}
