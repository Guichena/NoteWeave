package com.noteweave.config;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ProductionConfigurationGuard {

    private final String environment;
    private final String internalAuthToken;
    private final String researchInternalAuthToken;
    private final String artifactInternalAuthToken;
    private final String datasourcePassword;
    private final String minioSecretKey;
    private final String artifactCallbackSecret;
    private final boolean localUserFallback;
    private final boolean qaMysqlFallbackEnabled;
    private final boolean noteLocalMetadataFallbackEnabled;
    private final boolean quotaLocalFallbackEnabled;
    private final boolean researchRateLimitLocalFallbackEnabled;
    private final boolean llmTemplateFallbackEnabled;
    private final String artifactWorkerBaseUrl;
    private final String kafkaSecurityProtocol;
    private final boolean kafkaEnabled;
    private final String redisPassword;
    private final String elasticPassword;
    private final String kafkaSaslPassword;
    private final String minioAccessKey;
    private final String minioEndpoint;
    private final String elasticScheme;
    private final String[] activeProfiles;

    public ProductionConfigurationGuard(
            @Value("${noteweave.environment:local}") String environment,
            @Value("${noteweave.internal.auth-token:}") String internalAuthToken,
            @Value("${noteweave.internal.research-auth-token:}") String researchInternalAuthToken,
            @Value("${noteweave.internal.artifact-auth-token:}") String artifactInternalAuthToken,
            @Value("${spring.datasource.password:}") String datasourcePassword,
            @Value("${noteweave.storage.minio.secret-key:}") String minioSecretKey,
            @Value("${noteweave.worker.artifact-callback-secret:}") String artifactCallbackSecret,
            @Value("${noteweave.security.local-user-fallback:false}") boolean localUserFallback,
            @Value("${noteweave.retrieval.qa.mysql-fallback-enabled:false}") boolean qaMysqlFallbackEnabled,
            @Value("${noteweave.retrieval.note.local-metadata-fallback-enabled:false}")
            boolean noteLocalMetadataFallbackEnabled,
            @Value("${noteweave.quota.local-fallback-enabled:false}") boolean quotaLocalFallbackEnabled,
            @Value("${noteweave.research.agent.rate-limit.allow-local-fallback:false}")
            boolean researchRateLimitLocalFallbackEnabled,
            @Value("${noteweave.llm.template-fallback-enabled:false}") boolean llmTemplateFallbackEnabled,
            @Value("${noteweave.worker.artifact-base-url:http://localhost:18092}") String artifactWorkerBaseUrl,
            @Value("${spring.kafka.properties.security.protocol:PLAINTEXT}") String kafkaSecurityProtocol,
            @Value("${noteweave.kafka.enabled:true}") boolean kafkaEnabled,
            @Value("${spring.data.redis.password:}") String redisPassword,
            @Value("${noteweave.elasticsearch.password:}") String elasticPassword,
            @Value("${KAFKA_SASL_PASSWORD:}") String kafkaSaslPassword,
            @Value("${noteweave.storage.minio.access-key:}") String minioAccessKey,
            @Value("${noteweave.storage.minio.endpoint:http://localhost:9000}") String minioEndpoint,
            @Value("${noteweave.elasticsearch.scheme:http}") String elasticScheme,
            Environment springEnvironment
    ) {
        this.environment = environment;
        this.internalAuthToken = internalAuthToken;
        this.researchInternalAuthToken = researchInternalAuthToken;
        this.artifactInternalAuthToken = artifactInternalAuthToken;
        this.datasourcePassword = datasourcePassword;
        this.minioSecretKey = minioSecretKey;
        this.artifactCallbackSecret = artifactCallbackSecret;
        this.localUserFallback = localUserFallback;
        this.qaMysqlFallbackEnabled = qaMysqlFallbackEnabled;
        this.noteLocalMetadataFallbackEnabled = noteLocalMetadataFallbackEnabled;
        this.quotaLocalFallbackEnabled = quotaLocalFallbackEnabled;
        this.researchRateLimitLocalFallbackEnabled = researchRateLimitLocalFallbackEnabled;
        this.llmTemplateFallbackEnabled = llmTemplateFallbackEnabled;
        this.artifactWorkerBaseUrl = artifactWorkerBaseUrl;
        this.kafkaSecurityProtocol = kafkaSecurityProtocol;
        this.kafkaEnabled = kafkaEnabled;
        this.redisPassword = redisPassword;
        this.elasticPassword = elasticPassword;
        this.kafkaSaslPassword = kafkaSaslPassword;
        this.minioAccessKey = minioAccessKey;
        this.minioEndpoint = minioEndpoint;
        this.elasticScheme = elasticScheme;
        this.activeProfiles = springEnvironment.getActiveProfiles();
    }

    @PostConstruct
    void validateProductionConfiguration() {
        validateEnvironmentAlignment(environment, activeProfiles);
        if (isProduction(environment)) {
            validateKafkaEnabled(kafkaEnabled);
            validateNoteLocalFallback(noteLocalMetadataFallbackEnabled);
            validateResearchRateLimitLocalFallback(researchRateLimitLocalFallbackEnabled);
            validateProductionValues(internalAuthToken, researchInternalAuthToken, artifactInternalAuthToken,
                    datasourcePassword, minioSecretKey,
                    artifactCallbackSecret, localUserFallback, qaMysqlFallbackEnabled,
                    quotaLocalFallbackEnabled, llmTemplateFallbackEnabled, artifactWorkerBaseUrl,
                    kafkaSecurityProtocol, redisPassword, elasticPassword, kafkaSaslPassword,
                    minioAccessKey, minioEndpoint, elasticScheme);
        }
    }

    public static boolean isProduction(String environment) {
        return environment != null
                && ("production".equalsIgnoreCase(environment.trim())
                || "prod".equalsIgnoreCase(environment.trim()));
    }

    public static void validateEnvironmentAlignment(String environment, String... activeProfiles) {
        String normalized = environment == null ? "" : environment.trim().toLowerCase();
        if (!Arrays.asList("local", "development", "dev", "test", "staging", "stage",
                "production", "prod").contains(normalized)) {
            throw new IllegalStateException("Unknown NOTEWEAVE_ENVIRONMENT: " + environment);
        }
        boolean productionProfile = Arrays.stream(activeProfiles == null ? new String[0] : activeProfiles)
                .anyMatch(ProductionConfigurationGuard::isProduction);
        if (productionProfile != isProduction(normalized)) {
            throw new IllegalStateException(
                    "Spring production profile and NOTEWEAVE_ENVIRONMENT must agree");
        }
        boolean testProfile = Arrays.stream(activeProfiles == null ? new String[0] : activeProfiles)
                .anyMatch(profile -> "test".equalsIgnoreCase(profile));
        if (testProfile != "test".equals(normalized)) {
            throw new IllegalStateException("Spring test profile and NOTEWEAVE_ENVIRONMENT must agree");
        }
    }

    public static void validateNoteLocalFallback(boolean noteLocalMetadataFallbackEnabled) {
        if (noteLocalMetadataFallbackEnabled) {
            throw new IllegalStateException(
                    "Production must disable NOTEWEAVE_NOTE_LOCAL_METADATA_FALLBACK_ENABLED");
        }
    }

    public static void validateResearchRateLimitLocalFallback(boolean enabled) {
        if (enabled) {
            throw new IllegalStateException(
                    "Production must disable NOTEWEAVE_RESEARCH_AGENT_RATE_LIMIT_ALLOW_LOCAL_FALLBACK");
        }
    }

    public static void validateKafkaEnabled(boolean enabled) {
        if (!enabled) {
            throw new IllegalStateException(
                    "Production requires NOTEWEAVE_KAFKA_ENABLED=true for durable Artifact commands"
            );
        }
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
        validateValues(internalAuthToken, datasourcePassword, minioSecretKey,
                "test-artifact-callback-secret-with-entropy",
                localUserFallback, false);
    }

    public static void validateValues(
            String internalAuthToken,
            String datasourcePassword,
            String minioSecretKey,
            String artifactCallbackSecret,
            boolean localUserFallback
    ) {
        validateValues(internalAuthToken, datasourcePassword, minioSecretKey,
                artifactCallbackSecret, localUserFallback, false, false, false);
    }

    public static void validateValues(
            String internalAuthToken,
            String datasourcePassword,
            String minioSecretKey,
            String artifactCallbackSecret,
            boolean localUserFallback,
            boolean qaMysqlFallbackEnabled
    ) {
        validateValues(internalAuthToken, datasourcePassword, minioSecretKey,
                artifactCallbackSecret, localUserFallback, qaMysqlFallbackEnabled, false, false);
    }

    public static void validateValues(
            String internalAuthToken,
            String datasourcePassword,
            String minioSecretKey,
            String artifactCallbackSecret,
            boolean localUserFallback,
            boolean qaMysqlFallbackEnabled,
            boolean quotaLocalFallbackEnabled
    ) {
        validateValues(internalAuthToken, datasourcePassword, minioSecretKey,
                artifactCallbackSecret, localUserFallback, qaMysqlFallbackEnabled,
                quotaLocalFallbackEnabled, false);
    }

    public static void validateValues(
            String internalAuthToken,
            String datasourcePassword,
            String minioSecretKey,
            String artifactCallbackSecret,
            boolean localUserFallback,
            boolean qaMysqlFallbackEnabled,
            boolean quotaLocalFallbackEnabled,
            boolean llmTemplateFallbackEnabled
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
        if (isWeak(artifactCallbackSecret, "noteweave-artifact-callback-dev",
                "artifact-callback-secret", "replace-with-artifact-callback-secret")) {
            throw new IllegalStateException("Production requires a non-default NOTEWEAVE_ARTIFACT_CALLBACK_SECRET");
        }
        if (localUserFallback) {
            throw new IllegalStateException("Production must disable NOTEWEAVE_LOCAL_USER_FALLBACK");
        }
        if (qaMysqlFallbackEnabled) {
            throw new IllegalStateException("Production must disable NOTEWEAVE_QA_MYSQL_FALLBACK_ENABLED");
        }
        if (quotaLocalFallbackEnabled) {
            throw new IllegalStateException("Production must disable NOTEWEAVE_QUOTA_LOCAL_FALLBACK_ENABLED");
        }
        if (llmTemplateFallbackEnabled) {
            throw new IllegalStateException("Production must disable NOTEWEAVE_LLM_TEMPLATE_FALLBACK_ENABLED");
        }
    }

    public static void validateProductionValues(
            String internalAuthToken,
            String researchInternalAuthToken,
            String artifactInternalAuthToken,
            String datasourcePassword,
            String minioSecretKey,
            String artifactCallbackSecret,
            boolean localUserFallback,
            boolean qaMysqlFallbackEnabled,
            boolean quotaLocalFallbackEnabled,
            boolean llmTemplateFallbackEnabled,
            String artifactWorkerBaseUrl,
            String kafkaSecurityProtocol
    ) {
        validateProductionValues(
                internalAuthToken, researchInternalAuthToken, artifactInternalAuthToken,
                datasourcePassword, minioSecretKey, artifactCallbackSecret,
                localUserFallback, qaMysqlFallbackEnabled, quotaLocalFallbackEnabled,
                llmTemplateFallbackEnabled, artifactWorkerBaseUrl, kafkaSecurityProtocol,
                null, null, null, null, null, null
        );
    }

    public static void validateProductionValues(
            String internalAuthToken,
            String researchInternalAuthToken,
            String artifactInternalAuthToken,
            String datasourcePassword,
            String minioSecretKey,
            String artifactCallbackSecret,
            boolean localUserFallback,
            boolean qaMysqlFallbackEnabled,
            boolean quotaLocalFallbackEnabled,
            boolean llmTemplateFallbackEnabled,
            String artifactWorkerBaseUrl,
            String kafkaSecurityProtocol,
            String redisPassword,
            String elasticPassword,
            String kafkaSaslPassword,
            String minioAccessKey,
            String minioEndpoint,
            String elasticScheme
    ) {
        validateValues(internalAuthToken, datasourcePassword, minioSecretKey, artifactCallbackSecret,
                localUserFallback, qaMysqlFallbackEnabled, quotaLocalFallbackEnabled,
                llmTemplateFallbackEnabled);
        if (isWeak(researchInternalAuthToken, "replace-with-a-dedicated-research-worker-token")) {
            throw new IllegalStateException("Production requires a non-default NOTEWEAVE_RESEARCH_INTERNAL_AUTH_TOKEN");
        }
        if (isWeak(artifactInternalAuthToken, "replace-with-a-dedicated-artifact-worker-token")) {
            throw new IllegalStateException("Production requires a non-default NOTEWEAVE_ARTIFACT_INTERNAL_AUTH_TOKEN");
        }
        if (sameToken(internalAuthToken, researchInternalAuthToken)
                || sameToken(internalAuthToken, artifactInternalAuthToken)
                || sameToken(researchInternalAuthToken, artifactInternalAuthToken)) {
            throw new IllegalStateException("Production internal service tokens must be distinct per audience");
        }
        if (artifactWorkerBaseUrl == null || !artifactWorkerBaseUrl.trim().toLowerCase().startsWith("https://")) {
            throw new IllegalStateException("Production requires NOTEWEAVE_WORKER_ARTIFACT_BASE_URL to use HTTPS");
        }
        String protocol = kafkaSecurityProtocol == null ? "" : kafkaSecurityProtocol.trim().toUpperCase();
        if (!"SSL".equals(protocol) && !"SASL_SSL".equals(protocol)) {
            throw new IllegalStateException("Production requires Kafka security.protocol SSL or SASL_SSL");
        }
        if (redisPassword != null || elasticPassword != null || kafkaSaslPassword != null
                || minioAccessKey != null || minioEndpoint != null || elasticScheme != null) {
            requireProductionSecret(redisPassword, "REDIS_PASSWORD");
            requireProductionSecret(elasticPassword, "ELASTIC_PASSWORD");
            requireProductionSecret(kafkaSaslPassword, "KAFKA_SASL_PASSWORD");
            if (minioAccessKey == null || minioAccessKey.isBlank()) {
                throw new IllegalStateException("Production requires a non-empty MinIO access key");
            }
            if (minioEndpoint == null || !minioEndpoint.trim().toLowerCase().startsWith("https://")) {
                throw new IllegalStateException("Production requires the MinIO endpoint to use HTTPS");
            }
            if (elasticScheme == null || !"https".equalsIgnoreCase(elasticScheme.trim())) {
                throw new IllegalStateException("Production requires Elasticsearch HTTPS transport");
            }
        }
    }

    private static boolean sameToken(String left, String right) {
        return left != null && right != null && !left.isBlank() && left.trim().equals(right.trim());
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

    private static void requireProductionSecret(String value, String name) {
        if (isWeak(value, "password", "root", "redis", "elastic", "replace-with-a-strong-redis-password",
                "replace-with-a-strong-elasticsearch-password", "replace-with-a-strong-kafka-password")) {
            throw new IllegalStateException("Production requires a non-default " + name);
        }
        if (value.trim().length() < 16) {
            throw new IllegalStateException("Production requires " + name + " to contain at least 16 characters");
        }
    }
}
