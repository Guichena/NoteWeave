package com.noteweave.support;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public abstract class ContainerizedIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ContainerizedIntegrationTest.class);
    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.2");
    private static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8");
    private static final DockerImageName KAFKA_IMAGE =
            DockerImageName.parse("apache/kafka-native:3.8.0").asCompatibleSubstituteFor("apache/kafka");
    private static final DockerImageName MINIO_IMAGE = DockerImageName.parse("minio/minio:RELEASE.2025-04-22T22-12-26Z");
    private static final DockerImageName ELASTICSEARCH_IMAGE =
            DockerImageName.parse("elasticsearch:8.10.4").asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch");
    private static final String TEST_JWT_SECRET = "test-secret-key-test-secret-key-test-secret-key";
    private static final String TEST_RUN_ID = "tc-" + System.currentTimeMillis();
    private static final String TEST_DATABASE = "noteweave_test_" + TEST_RUN_ID.replace('-', '_');
    private static final String TASK_TOPIC = "test.noteweave.task." + TEST_RUN_ID;
    private static final String TASK_CONSUMER_GROUP = "test-noteweave-task-worker-" + TEST_RUN_ID;
    private static final String DOCUMENT_TOPIC = "test.noteweave.document." + TEST_RUN_ID;
    private static final String DOCUMENT_CONSUMER_GROUP = "test-noteweave-document-worker-" + TEST_RUN_ID;
    private static final String LOCAL_MYSQL_HOST = env("NOTEWEAVE_TEST_MYSQL_HOST", "localhost");
    private static final int LOCAL_MYSQL_PORT = Integer.parseInt(env("NOTEWEAVE_TEST_MYSQL_PORT", "13307"));
    private static final String LOCAL_MYSQL_USERNAME = env("NOTEWEAVE_TEST_MYSQL_USERNAME", "root");
    private static final String LOCAL_MYSQL_PASSWORD = env("NOTEWEAVE_TEST_MYSQL_PASSWORD", "root");
    private static final String LOCAL_REDIS_HOST = env("NOTEWEAVE_TEST_REDIS_HOST", "localhost");
    private static final int LOCAL_REDIS_PORT = Integer.parseInt(env("NOTEWEAVE_TEST_REDIS_PORT", "6380"));
    private static final int LOCAL_REDIS_DATABASE = Integer.parseInt(env("NOTEWEAVE_TEST_REDIS_DATABASE", "15"));
    private static final String LOCAL_KAFKA_BOOTSTRAP = env("NOTEWEAVE_TEST_KAFKA_BOOTSTRAP", "localhost:19092");
    private static final String LOCAL_MINIO_ENDPOINT = env("NOTEWEAVE_TEST_MINIO_ENDPOINT", "http://localhost:19100");
    private static final String LOCAL_MINIO_ACCESS_KEY = env("NOTEWEAVE_TEST_MINIO_ACCESS_KEY", "noteweave");
    private static final String LOCAL_MINIO_SECRET_KEY = env("NOTEWEAVE_TEST_MINIO_SECRET_KEY", "noteweave-minio-secret");
    private static final String LOCAL_MINIO_BUCKET = env("NOTEWEAVE_TEST_MINIO_BUCKET", "noteweave-dev");
    private static final String LOCAL_MINIO_TEST_BUCKET = env("NOTEWEAVE_TEST_MINIO_TEST_BUCKET", "noteweave-test");
    private static final String LOCAL_ES_URIS = env("NOTEWEAVE_TEST_ES_URIS", "http://localhost:19200");
    private static final String LOCAL_MYSQL_ADMIN_JDBC_URL =
            "jdbc:mysql://" + LOCAL_MYSQL_HOST + ":" + LOCAL_MYSQL_PORT
                    + "/?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    private static final String LOCAL_MYSQL_TEST_JDBC_URL =
            "jdbc:mysql://" + LOCAL_MYSQL_HOST + ":" + LOCAL_MYSQL_PORT + "/" + TEST_DATABASE
                    + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";

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

    private static final ElasticsearchContainer ELASTICSEARCH_CONTAINER = new ElasticsearchContainer(ELASTICSEARCH_IMAGE)
            .withEnv("xpack.security.enabled", "false")
            .withEnv("xpack.security.http.ssl.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms256m -Xmx256m");
    private static final boolean USE_TESTCONTAINERS;

    static {
        boolean useTestcontainers = false;
        try {
            Startables.deepStart(MYSQL_CONTAINER, REDIS_CONTAINER, KAFKA_CONTAINER, MINIO_CONTAINER, ELASTICSEARCH_CONTAINER).join();
            ensureKafkaTopic(KAFKA_CONTAINER.getBootstrapServers());
            useTestcontainers = true;
            log.info("Using Testcontainers-backed integration services");
        } catch (Throwable ex) {
            log.warn("Testcontainers unavailable, falling back to local Docker services", ex);
            initializeLocalFallback();
        }
        USE_TESTCONTAINERS = useTestcontainers;
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        if (USE_TESTCONTAINERS) {
            registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
            registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
            registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
            registry.add("spring.datasource.driver-class-name", MYSQL_CONTAINER::getDriverClassName);
            registry.add("spring.data.redis.host", REDIS_CONTAINER::getHost);
            registry.add("spring.data.redis.port", () -> REDIS_CONTAINER.getMappedPort(6379));
            registry.add("spring.data.redis.database", () -> 0);
            registry.add("spring.kafka.bootstrap-servers", KAFKA_CONTAINER::getBootstrapServers);
            registry.add("noteweave.storage.minio.endpoint", MINIO_CONTAINER::getS3URL);
            registry.add("noteweave.storage.minio.access-key", MINIO_CONTAINER::getUserName);
            registry.add("noteweave.storage.minio.secret-key", MINIO_CONTAINER::getPassword);
            registry.add("noteweave.elasticsearch.uris", ELASTICSEARCH_CONTAINER::getHttpHostAddress);
        } else {
            registry.add("spring.datasource.url", () -> LOCAL_MYSQL_TEST_JDBC_URL);
            registry.add("spring.datasource.username", () -> LOCAL_MYSQL_USERNAME);
            registry.add("spring.datasource.password", () -> LOCAL_MYSQL_PASSWORD);
            registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
            registry.add("spring.data.redis.host", () -> LOCAL_REDIS_HOST);
            registry.add("spring.data.redis.port", () -> LOCAL_REDIS_PORT);
            registry.add("spring.data.redis.database", () -> LOCAL_REDIS_DATABASE);
            registry.add("spring.kafka.bootstrap-servers", () -> LOCAL_KAFKA_BOOTSTRAP);
            registry.add("noteweave.storage.minio.endpoint", () -> LOCAL_MINIO_ENDPOINT);
            registry.add("noteweave.storage.minio.access-key", () -> LOCAL_MINIO_ACCESS_KEY);
            registry.add("noteweave.storage.minio.secret-key", () -> LOCAL_MINIO_SECRET_KEY);
            registry.add("noteweave.elasticsearch.uris", () -> LOCAL_ES_URIS);
        }
        registry.add("noteweave.kafka.topics.task", () -> TASK_TOPIC);
        registry.add("noteweave.kafka.consumer-groups.task", () -> TASK_CONSUMER_GROUP);
        registry.add("noteweave.kafka.topics.document-process", () -> DOCUMENT_TOPIC);
        registry.add("noteweave.kafka.consumer-groups.document-process", () -> DOCUMENT_CONSUMER_GROUP);
        registry.add("noteweave.storage.minio.bucket", () -> USE_TESTCONTAINERS ? "noteweave-dev" : LOCAL_MINIO_BUCKET);
        registry.add("noteweave.storage.minio.test-bucket", () -> USE_TESTCONTAINERS ? "noteweave-test" : LOCAL_MINIO_TEST_BUCKET);
        registry.add("noteweave.storage.paths.local-test-root", () -> "target/noteweave-test");
        registry.add("noteweave.storage.paths.dev-object-prefix", () -> "dev");
        registry.add("noteweave.storage.paths.test-object-prefix", () -> "test");
        registry.add("noteweave.storage.paths.test-run-id", () -> TEST_RUN_ID);
        registry.add("noteweave.elasticsearch.index-prefix", () -> "noteweave-test-" + TEST_RUN_ID + "-");
        registry.add("jwt.secret-key", () -> TEST_JWT_SECRET);
    }

    private static void initializeLocalFallback() {
        createLocalDatabase();
        clearLocalRedisDatabase();
        ensureKafkaTopic(LOCAL_KAFKA_BOOTSTRAP);
        ensureMinioBucketExists(LOCAL_MINIO_BUCKET);
        ensureMinioBucketExists(LOCAL_MINIO_TEST_BUCKET);
    }

    private static void createLocalDatabase() {
        try (Connection connection = DriverManager.getConnection(
                LOCAL_MYSQL_ADMIN_JDBC_URL,
                LOCAL_MYSQL_USERNAME,
                LOCAL_MYSQL_PASSWORD
        ); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS `" + TEST_DATABASE + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to prepare local MySQL test database", ex);
        }
    }

    private static void ensureMinioBucketExists(String bucketName) {
        try {
            MinioClient minioClient = MinioClient.builder()
                    .endpoint(LOCAL_MINIO_ENDPOINT)
                    .credentials(LOCAL_MINIO_ACCESS_KEY, LOCAL_MINIO_SECRET_KEY)
                    .build();
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to prepare local MinIO bucket " + bucketName, ex);
        }
    }

    private static void clearLocalRedisDatabase() {
        try (Socket socket = new Socket(LOCAL_REDIS_HOST, LOCAL_REDIS_PORT);
             InputStream in = new BufferedInputStream(socket.getInputStream());
             OutputStream out = new BufferedOutputStream(socket.getOutputStream())) {
            writeRedisCommand(out, "SELECT", String.valueOf(LOCAL_REDIS_DATABASE));
            expectRedisOk(in, "SELECT");
            writeRedisCommand(out, "FLUSHDB");
            expectRedisOk(in, "FLUSHDB");
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to clear local Redis test database", ex);
        }
    }

    private static void ensureKafkaTopic(String bootstrapServers) {
        Map<String, Object> config = Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers
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

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static void writeRedisCommand(OutputStream out, String... parts) throws Exception {
        out.write(("*" + parts.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String part : parts) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            out.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        out.flush();
    }

    private static void expectRedisOk(InputStream in, String commandName) throws Exception {
        int prefix = in.read();
        if (prefix == -1) {
            throw new EOFException("No response from Redis for " + commandName);
        }
        String line = readRedisLine(in);
        if (prefix == '+') {
            return;
        }
        if (prefix == '-') {
            throw new IllegalStateException("Redis command " + commandName + " failed: " + line);
        }
        throw new IllegalStateException("Unexpected Redis response for " + commandName + ": " + (char) prefix + line);
    }

    private static String readRedisLine(InputStream in) throws Exception {
        StringBuilder builder = new StringBuilder();
        while (true) {
            int ch = in.read();
            if (ch == -1) {
                throw new EOFException("Unexpected end of Redis response");
            }
            if (ch == '\r') {
                int next = in.read();
                if (next != '\n') {
                    throw new IllegalStateException("Malformed Redis response");
                }
                return builder.toString();
            }
            builder.append((char) ch);
        }
    }
}
