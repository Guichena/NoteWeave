package com.noteweave;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.config.ProductionConfigurationGuard;
import org.junit.jupiter.api.Test;

class ProductionConfigurationGuardTest {

    @Test
    void productionMustRejectDevelopmentCredentials() {
        assertThatThrownBy(() -> ProductionConfigurationGuard.validateValues(
                "noteweave-internal-dev", "noteweave123", "minioadmin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOTEWEAVE_INTERNAL_AUTH_TOKEN");
    }

    @Test
    void productionAcceptsExplicitNonDefaultCredentials() {
        assertThatCode(() -> ProductionConfigurationGuard.validateValues(
                "token-with-entropy", "database-secret-2026", "minio-secret-2026"))
                .doesNotThrowAnyException();
    }

    @Test
    void productionMustRejectLocalUserFallback() {
        assertThatThrownBy(() -> ProductionConfigurationGuard.validateValues(
                "token-with-entropy", "database-secret-2026", "minio-secret-2026", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOTEWEAVE_LOCAL_USER_FALLBACK");
    }
}
