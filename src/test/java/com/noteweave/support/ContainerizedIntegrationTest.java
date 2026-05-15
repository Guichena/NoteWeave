package com.noteweave.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public abstract class ContainerizedIntegrationTest {

    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.2");
    private static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8");
    private static final DockerImageName KAFKA_IMAGE =
            DockerImageName.parse("apache/kafka-native:3.8.0").asCompatibleSubstituteFor("apache/kafka");
    private static final DockerImageName MINIO_IMAGE = DockerImageName.parse("minio/minio:RELEASE.2025-04-22T22-12-26Z");
    private static final String TEST_JWT_SECRET = "test-secret-key-test-secret-key-test-secret-key";

    private static final MySQLContainer<?> MYSQL_CONTAINER = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName("noteweave")
            .withUsername("noteweave")
            .withPassword("noteweave");

    private static final GenericContainer<?> REDIS_CONTAINER = new GenericContainer<>(REDIS_IMAGE)
            .withExposedPorts(6379);

    private static final KafkaContainer KAFKA_CONTAINER = new KafkaContainer(KAFKA_IMAGE);

    private static final MinIOContainer MINIO_CONTAINER = new MinIOContainer(MINIO_IMAGE)
            .withUserName("noteweave")
            .withPassword("noteweave-minio-secret");

    private static final String TEST_RUN_ID = "tc-" + System.currentTimeMillis();
    private static final String TASK_TOPIC = "test.noteweave.task." + TEST_RUN_ID;
    private static final String TASK_CONSUMER_GROUP = "test-noteweave-task-worker-" + TEST_RUN_ID;
    private static final String DOCUMENT_TOPIC = "test.noteweave.document." + TEST_RUN_ID;

    static {
        Startables.deepStart(MYSQL_CONTAINER, REDIS_CONTAINER, KAFKA_CONTAINER, MINIO_CONTAINER).join();
        ensureKafkaTopic();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL_CONTAINER::getDriverClassName);
        registry.add("spring.data.redis.host", REDIS_CONTAINER::getHost);
        registry.add("spring.data.redis.port", () -> REDIS_CONTAINER.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", KAFKA_CONTAINER::getBootstrapServers);
        registry.add("noteweave.kafka.topics.task", () -> TASK_TOPIC);
        registry.add("noteweave.kafka.consumer-groups.task", () -> TASK_CONSUMER_GROUP);
        registry.add("noteweave.kafka.topics.document-process", () -> DOCUMENT_TOPIC);
        registry.add("noteweave.storage.minio.endpoint", MINIO_CONTAINER::getS3URL);
        registry.add("noteweave.storage.minio.access-key", MINIO_CONTAINER::getUserName);
        registry.add("noteweave.storage.minio.secret-key", MINIO_CONTAINER::getPassword);
        registry.add("noteweave.storage.minio.bucket", () -> "noteweave-dev");
        registry.add("noteweave.storage.minio.test-bucket", () -> "noteweave-test");
        registry.add("noteweave.storage.paths.local-test-root", () -> "target/noteweave-test");
        registry.add("noteweave.storage.paths.dev-object-prefix", () -> "dev");
        registry.add("noteweave.storage.paths.test-object-prefix", () -> "test");
        registry.add("noteweave.storage.paths.test-run-id", () -> TEST_RUN_ID);
        registry.add("jwt.secret-key", () -> TEST_JWT_SECRET);
    }

    private static void ensureKafkaTopic() {
        Map<String, Object> config = Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_CONTAINER.getBootstrapServers()
        );
        try (AdminClient adminClient = AdminClient.create(config)) {
            adminClient.createTopics(java.util.List.of(
                            new NewTopic(TASK_TOPIC, 1, (short) 1),
                            new NewTopic(DOCUMENT_TOPIC, 1, (short) 1)
                    ))
                    .all()
                    .get(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Topic may already exist or auto-create may be enabled; either case is acceptable.
        }
    }
}
