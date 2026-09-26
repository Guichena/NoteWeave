package com.noteweave;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.infra.ElasticsearchConfig;
import com.noteweave.infra.KafkaConfig;
import com.noteweave.infra.KafkaMessagePublisher;
import com.noteweave.infra.KafkaTaskConsumer;
import com.noteweave.infra.LocalObjectStorage;
import com.noteweave.infra.MinioObjectStorage;
import com.noteweave.storage.ObjectStorage;
import com.noteweave.infra.RedisConfig;
import com.noteweave.worker.ArtifactOutboxPublisher;
import com.noteweave.worker.KafkaArtifactOutboxPublisher;
import com.noteweave.worker.KafkaDisabledArtifactOutboxPublisher;
import java.nio.file.Path;
import java.util.Map;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class InfrastructureConfigurationCombinationTest {

    @Test
    void elasticsearchPropertiesShouldBindFalseAndCredentialsThroughCanonicalConstructor() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "noteweave.elasticsearch.enabled", "false",
                "noteweave.elasticsearch.host", "search.internal",
                "noteweave.elasticsearch.port", "9443",
                "noteweave.elasticsearch.scheme", "https",
                "noteweave.elasticsearch.index-prefix", "nw",
                "noteweave.elasticsearch.username", "reader",
                "noteweave.elasticsearch.password", "secret"
        ));

        NoteWeaveProperties properties = new Binder(source)
                .bind("noteweave", Bindable.of(NoteWeaveProperties.class))
                .orElseThrow(() -> new AssertionError("NoteWeave properties did not bind"));

        assertThat(properties.elasticsearch().enabled()).isFalse();
        assertThat(properties.elasticsearch().host()).isEqualTo("search.internal");
        assertThat(properties.elasticsearch().port()).isEqualTo(9443);
        assertThat(properties.elasticsearch().username()).isEqualTo("reader");
        assertThat(properties.elasticsearch().password()).isEqualTo("secret");
    }

    @Test
    void kafkaDisabledShouldNotCreateKafkaInfrastructure() {
        new ApplicationContextRunner()
                .withUserConfiguration(KafkaConfig.class)
                .withPropertyValues(
                        "noteweave.kafka.enabled=false",
                        "spring.kafka.bootstrap-servers=127.0.0.1:1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(KafkaTemplate.class);
                });
    }

    @Test
    void kafkaDisabledShouldNotCreateTaskConsumers() {
        new ApplicationContextRunner()
                .withUserConfiguration(KafkaTaskConsumer.class)
                .withPropertyValues("noteweave.kafka.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(KafkaTaskConsumer.class);
                });
    }

    @Test
    void kafkaEnabledShouldCreateKafkaInfrastructureWithoutConnectingAtStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(KafkaConfig.class)
                .withPropertyValues(
                        "noteweave.kafka.enabled=true",
                        "spring.kafka.bootstrap-servers=127.0.0.1:1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(KafkaTemplate.class);
                    assertThat(context).hasSingleBean(KafkaMessagePublisher.class);
                });
    }

    @Test
    void kafkaToggleShouldSelectExactlyOneArtifactPublisherWithoutHttpFallback() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(KafkaMessagePublisher.class, () -> (topic, key, payload) -> { })
                .withUserConfiguration(
                        KafkaArtifactOutboxPublisher.class,
                        KafkaDisabledArtifactOutboxPublisher.class
                );

        runner.withPropertyValues("noteweave.kafka.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ArtifactOutboxPublisher.class);
            assertThat(context.getBean(ArtifactOutboxPublisher.class))
                    .isInstanceOf(KafkaArtifactOutboxPublisher.class);
        });

        runner.withPropertyValues("noteweave.kafka.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ArtifactOutboxPublisher.class);
            assertThat(context.getBean(ArtifactOutboxPublisher.class))
                    .isInstanceOf(KafkaDisabledArtifactOutboxPublisher.class);
        });
    }

    @Test
    void elasticsearchToggleShouldControlAllClientBeans() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(NoteWeaveProperties.class, this::properties)
                .withUserConfiguration(ElasticsearchConfig.class);

        runner.withPropertyValues("noteweave.elasticsearch.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(RestClient.class);
                    assertThat(context).doesNotHaveBean(ElasticsearchClient.class);
                });

        runner.withPropertyValues("noteweave.elasticsearch.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RestClient.class);
                    assertThat(context).hasSingleBean(ElasticsearchClient.class);
                });
    }

    @Test
    void storageBackendShouldSelectExactlyOneAdapter() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(NoteWeaveProperties.class, this::properties)
                .withUserConfiguration(LocalObjectStorage.class, MinioObjectStorage.class);

        runner.withPropertyValues("noteweave.storage.backend=local")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ObjectStorage.class);
                    assertThat(context.getBean(ObjectStorage.class)).isInstanceOf(LocalObjectStorage.class);
                });
    }

    @Test
    void redisToggleShouldControlConnectionFactoryAndTemplate() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(RedisConfig.class)
                .withPropertyValues("spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1");

        runner.withPropertyValues("noteweave.redis.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(RedisConnectionFactory.class);
            assertThat(context).doesNotHaveBean(StringRedisTemplate.class);
        });
        runner.withPropertyValues("noteweave.redis.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(RedisConnectionFactory.class);
            assertThat(context).hasSingleBean(StringRedisTemplate.class);
        });
    }

    private NoteWeaveProperties properties() {
        return new NoteWeaveProperties(
                new NoteWeaveProperties.Storage(
                        "local",
                        Path.of("target/config-combination-storage"),
                        new NoteWeaveProperties.Minio(
                                "http://127.0.0.1:1", "access", "secret", "us-east-1",
                                "source", "derived", "export")),
                null, null, null,
                new NoteWeaveProperties.Elasticsearch(true, "127.0.0.1", 1, "http", "test"),
                null
        );
    }
}
