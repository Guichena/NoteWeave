package com.noteweave;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void productionMustRequireWorkerSpecificCallbackSecrets() {
        assertThatThrownBy(() -> ProductionConfigurationGuard.validateValues(
                "token-with-entropy", "database-secret-2026", "minio-secret-2026",
                "noteweave-artifact-callback-dev", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOTEWEAVE_ARTIFACT_CALLBACK_SECRET");
    }

    @Test
    void productionMustRejectQaMysqlFallback() {
        assertThatThrownBy(() -> ProductionConfigurationGuard.validateValues(
                "token-with-entropy", "database-secret-2026", "minio-secret-2026",
                "artifact-callback-secret-with-entropy", false, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOTEWEAVE_QA_MYSQL_FALLBACK_ENABLED");
    }

    @Test
    void productionMustRejectLocalQuotaFallback() {
        assertThatThrownBy(() -> ProductionConfigurationGuard.validateValues(
                "token-with-entropy", "database-secret-2026", "minio-secret-2026",
                "artifact-callback-secret-with-entropy", false, false, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOTEWEAVE_QUOTA_LOCAL_FALLBACK_ENABLED");
    }

    @Test
    void productionMustRejectAnswerTemplateFallback() {
        assertThatThrownBy(() -> ProductionConfigurationGuard.validateValues(
                "token-with-entropy", "database-secret-2026", "minio-secret-2026",
                "artifact-callback-secret-with-entropy", false, false, false, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOTEWEAVE_LLM_TEMPLATE_FALLBACK_ENABLED");
    }

    @Test
    void environmentNamesShouldDriveProductionGuardIndependentlyOfSpringProfile() {
        assertThat(ProductionConfigurationGuard.isProduction("production")).isTrue();
        assertThat(ProductionConfigurationGuard.isProduction("prod")).isTrue();
        assertThat(ProductionConfigurationGuard.isProduction("development")).isFalse();
        assertThat(ProductionConfigurationGuard.isProduction("local")).isFalse();
    }

    @Test
    void productionMustRequireDistinctAudienceScopedInternalTokens() {
        assertThatThrownBy(() -> ProductionConfigurationGuard.validateProductionValues(
                "coordinator-token-with-entropy", "shared-token-with-entropy", "shared-token-with-entropy",
                "database-secret-2026", "minio-secret-2026", "artifact-callback-secret-with-entropy",
                false, false, false, false, "https://artifact-worker.internal", "SASL_SSL"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distinct per audience");
    }

    @Test
    void productionMustRejectPlaintextInternalTransport() {
        assertThatThrownBy(() -> ProductionConfigurationGuard.validateProductionValues(
                "coordinator-token-with-entropy", "research-token-with-entropy", "artifact-token-with-entropy",
                "database-secret-2026", "minio-secret-2026", "artifact-callback-secret-with-entropy",
                false, false, false, false, "http://artifact-worker.internal", "SASL_SSL"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");

        assertThatThrownBy(() -> ProductionConfigurationGuard.validateProductionValues(
                "coordinator-token-with-entropy", "research-token-with-entropy", "artifact-token-with-entropy",
                "database-secret-2026", "minio-secret-2026", "artifact-callback-secret-with-entropy",
                false, false, false, false, "https://artifact-worker.internal", "SASL_PLAINTEXT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SASL_SSL");
    }
}
