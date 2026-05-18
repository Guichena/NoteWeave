package com.noteweave.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.admin.dto.ComponentHealthResponse;
import com.noteweave.admin.dto.SystemHealthResponse;
import com.noteweave.admin.dto.SystemHealthSnapshotResponse;
import com.noteweave.admin.model.SystemHealthComponent;
import com.noteweave.admin.model.SystemHealthSnapshot;
import com.noteweave.admin.model.SystemHealthStatus;
import com.noteweave.admin.repository.SystemHealthSnapshotRepository;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.llm.config.LlmProperties;
import com.noteweave.search.service.SearchIndexService;
import com.noteweave.storage.service.FileStorageService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

@Service
@RequiredArgsConstructor
public class SystemHealthService {

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final FileStorageService fileStorageService;
    private final AdminStorageSupport adminStorageSupport;
    private final SearchIndexService searchIndexService;
    private final LlmProperties llmProperties;
    private final SystemHealthSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String kafkaBootstrapServers;

    @Value("${noteweave.kafka.topics.task:noteweave.task}")
    private String taskTopic;

    @Transactional
    public SystemHealthResponse checkAll() {
        LocalDateTime now = LocalDateTime.now();
        List<ComponentHealthResponse> components = List.of(
                check(SystemHealthComponent.MYSQL),
                check(SystemHealthComponent.REDIS),
                check(SystemHealthComponent.MINIO),
                check(SystemHealthComponent.KAFKA),
                check(SystemHealthComponent.ELASTICSEARCH),
                check(SystemHealthComponent.LLM_PROVIDER)
        );
        return SystemHealthResponse.builder()
                .components(components)
                .checkedAt(now)
                .build();
    }

    @Transactional
    public ComponentHealthResponse check(SystemHealthComponent component) {
        ComponentHealthResponse response = switch (component) {
            case MYSQL -> checkMysql();
            case REDIS -> checkRedis();
            case MINIO -> checkMinio();
            case KAFKA -> checkKafka();
            case ELASTICSEARCH -> checkElasticsearch();
            case LLM_PROVIDER -> checkLlmProvider();
        };
        saveSnapshot(response);
        return ComponentHealthResponse.builder()
                .component(response.getComponent())
                .status(response.getStatus())
                .latencyMs(response.getLatencyMs())
                .detail(response.getDetail())
                .checkedAt(response.getCheckedAt())
                .recentSnapshots(recentSnapshots(component))
                .build();
    }

    @Transactional(readOnly = true)
    public List<SystemHealthSnapshotResponse> recentSnapshots(SystemHealthComponent component) {
        return snapshotRepository.findTop20ByComponentOrderByCheckedAtDesc(component).stream()
                .map(this::toSnapshotResponse)
                .toList();
    }

    private ComponentHealthResponse checkMysql() {
        return measure(SystemHealthComponent.MYSQL, () -> {
            Integer value = jdbcTemplate.queryForObject("select 1", Integer.class);
            return new ProbeResult(value != null && value == 1 ? SystemHealthStatus.UP : SystemHealthStatus.DOWN, Map.of("result", value));
        });
    }

    private ComponentHealthResponse checkRedis() {
        String key = "noteweave:health:" + System.nanoTime();
        return measure(SystemHealthComponent.REDIS, () -> {
            stringRedisTemplate.opsForValue().set(key, "ok", Duration.ofSeconds(10));
            String value = stringRedisTemplate.opsForValue().get(key);
            stringRedisTemplate.delete(key);
            SystemHealthStatus status = "ok".equals(value) ? SystemHealthStatus.UP : SystemHealthStatus.DOWN;
            return new ProbeResult(status, Map.of("echo", value));
        });
    }

    private ComponentHealthResponse checkMinio() {
        return measure(SystemHealthComponent.MINIO, () -> {
            String bucket = adminStorageSupport.currentBucket();
            boolean exists = fileStorageService.bucketExists(bucket);
            int objectCount = fileStorageService.listObjectKeys(bucket, adminStorageSupport.currentObjectPrefix()).size();
            SystemHealthStatus status = exists ? SystemHealthStatus.UP : SystemHealthStatus.DOWN;
            return new ProbeResult(status, Map.of(
                    "bucket", bucket,
                    "prefix", adminStorageSupport.currentObjectPrefix(),
                    "objectCount", objectCount
            ));
        });
    }

    private ComponentHealthResponse checkKafka() {
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);
        return measure(SystemHealthComponent.KAFKA, () -> {
            try (AdminClient adminClient = AdminClient.create(properties)) {
                var description = adminClient.describeTopics(List.of(taskTopic)).allTopicNames().get();
                return new ProbeResult(SystemHealthStatus.UP, Map.of(
                        "bootstrapServers", kafkaBootstrapServers,
                        "topic", taskTopic,
                        "partitionCount", description.get(taskTopic).partitions().size()
                ));
            }
        });
    }

    private ComponentHealthResponse checkElasticsearch() {
        return measure(SystemHealthComponent.ELASTICSEARCH, () -> {
            JsonNode cluster = searchIndexService.clusterHealth();
            boolean vectorAliasExists = searchIndexService.aliasExists(searchIndexService.documentChunkVectorAliasName());
            String clusterStatus = cluster.path("status").asText("unknown");
            SystemHealthStatus status = switch (clusterStatus.toLowerCase()) {
                case "green" -> SystemHealthStatus.UP;
                case "yellow" -> SystemHealthStatus.DEGRADED;
                case "red" -> SystemHealthStatus.DOWN;
                default -> SystemHealthStatus.UNKNOWN;
            };
            return new ProbeResult(status, Map.of(
                    "clusterStatus", clusterStatus,
                    "documentIndex", searchIndexService.documentChunkIndexName(),
                    "vectorAlias", searchIndexService.documentChunkVectorAliasName(),
                    "vectorAliasExists", vectorAliasExists
            ));
        });
    }

    private ComponentHealthResponse checkLlmProvider() {
        if (llmProperties.stub() != null && llmProperties.stub().enabled()) {
            return response(SystemHealthComponent.LLM_PROVIDER, SystemHealthStatus.UP, 1L, Map.of(
                    "provider", llmProperties.stub().provider(),
                    "model", llmProperties.stub().model(),
                    "stub", true
            ));
        }
        if (llmProperties.api() == null || llmProperties.api().apiKey() == null || llmProperties.api().apiKey().isBlank()) {
            return response(SystemHealthComponent.LLM_PROVIDER, SystemHealthStatus.DOWN, 1L, Map.of("error", "LLM api key is missing"));
        }
        return measure(SystemHealthComponent.LLM_PROVIDER, () -> {
            WebClient webClient = WebClient.builder().baseUrl(llmProperties.api().baseUrl()).build();
            Integer statusCode = webClient.get()
                    .uri("/models")
                    .headers(headers -> headers.setBearerAuth(llmProperties.api().apiKey()))
                    .retrieve()
                    .toBodilessEntity()
                    .map(entity -> entity.getStatusCode().value())
                    .block(Duration.ofSeconds(Math.max(5, llmProperties.api().timeoutSeconds() == null ? 10 : llmProperties.api().timeoutSeconds())));
            SystemHealthStatus status = statusCode != null && statusCode < 300 ? SystemHealthStatus.UP : SystemHealthStatus.DEGRADED;
            return new ProbeResult(status, Map.of(
                    "baseUrl", llmProperties.api().baseUrl(),
                    "model", llmProperties.api().model(),
                    "statusCode", statusCode
            ));
        }, ex -> SystemHealthStatus.DEGRADED, () -> Map.of(
                "baseUrl", llmProperties.api().baseUrl(),
                "model", llmProperties.api().model()
        ));
    }

    private ComponentHealthResponse measure(SystemHealthComponent component, HealthProbe probe) {
        return measure(component, probe, ex -> SystemHealthStatus.DOWN, Map::of);
    }

    private ComponentHealthResponse measure(
            SystemHealthComponent component,
            HealthProbe probe,
            java.util.function.Function<Exception, SystemHealthStatus> statusResolver,
            java.util.function.Supplier<Map<String, Object>> baseDetailSupplier
    ) {
        Instant start = Instant.now();
        try {
            ProbeResult result = probe.run();
            return response(component, result.status(), Math.max(1L, Duration.between(start, Instant.now()).toMillis()), result.detail());
        } catch (Exception ex) {
            java.util.LinkedHashMap<String, Object> detail = new java.util.LinkedHashMap<>(baseDetailSupplier.get());
            detail.put("error", trim(ex.getMessage()));
            return response(component, statusResolver.apply(ex), Math.max(1L, Duration.between(start, Instant.now()).toMillis()), detail);
        }
    }

    private ComponentHealthResponse response(SystemHealthComponent component, SystemHealthStatus status, long latencyMs, Map<String, Object> detail) {
        return ComponentHealthResponse.builder()
                .component(component)
                .status(status)
                .latencyMs(latencyMs)
                .detail(objectMapper.valueToTree(detail))
                .checkedAt(LocalDateTime.now())
                .build();
    }

    private void saveSnapshot(ComponentHealthResponse response) {
        SystemHealthSnapshot snapshot = new SystemHealthSnapshot();
        snapshot.setComponent(response.getComponent());
        snapshot.setStatus(response.getStatus());
        snapshot.setLatencyMs(response.getLatencyMs());
        snapshot.setDetailJson(writeJson(response.getDetail()));
        snapshot.setCheckedAt(response.getCheckedAt());
        snapshotRepository.save(snapshot);
    }

    private SystemHealthSnapshotResponse toSnapshotResponse(SystemHealthSnapshot snapshot) {
        return SystemHealthSnapshotResponse.builder()
                .id(snapshot.getId())
                .component(snapshot.getComponent())
                .status(snapshot.getStatus())
                .latencyMs(snapshot.getLatencyMs())
                .detail(readJson(snapshot.getDetailJson()))
                .checkedAt(snapshot.getCheckedAt())
                .build();
    }

    private String writeJson(JsonNode detail) {
        try {
            return detail == null ? null : objectMapper.writeValueAsString(detail);
        } catch (Exception ex) {
            return null;
        }
    }

    private JsonNode readJson(String value) {
        try {
            return value == null ? objectMapper.nullNode() : objectMapper.readTree(value);
        } catch (Exception ex) {
            return objectMapper.nullNode();
        }
    }

    private String trim(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    @FunctionalInterface
    private interface HealthProbe {
        ProbeResult run() throws Exception;
    }

    private record ProbeResult(SystemHealthStatus status, Map<String, Object> detail) {
    }
}
