package com.noteweave.retrieval.eval;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.noteweave.search.ChunkSearchPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

class RetrievalEvaluationCliConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("spring.profiles.active=retrieval-evaluation-cli")
            .withUserConfiguration(RetrievalEvaluationCliConfiguration.class);

    @Test
    void shouldLoadEvaluationBeansWithoutWebKafkaJdbcOrFlywayRuntime() {
        contextRunner.withPropertyValues("noteweave.elasticsearch.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(ChunkSearchPort.class);
                    assertThat(context).hasSingleBean(RetrievalSnapshotSanitizer.class);
                    assertThat(context).hasSingleBean(QaGoldAnnotationDraftCapture.class);
                    assertThat(context).hasSingleBean(QaGoldAnnotationCompiler.class);
                    assertThat(context).doesNotHaveBean(ElasticsearchClient.class);
                    assertThat(context).doesNotHaveBean(JdbcTemplate.class);
                    assertThat(context).doesNotHaveBean(KafkaListenerEndpointRegistry.class);
                    assertThat(context).doesNotHaveBean(Flyway.class);
                });
    }

    @Test
    void shouldProvisionOnlyTheElasticsearchClientWhenEvaluationIsEnabled() {
        contextRunner.withPropertyValues(
                        "noteweave.elasticsearch.enabled=true",
                        "noteweave.elasticsearch.host=localhost",
                        "noteweave.elasticsearch.port=9200",
                        "noteweave.elasticsearch.scheme=http",
                        "noteweave.elasticsearch.index-prefix=noteweave_chunk")
                .run(context -> {
                    assertThat(context).hasSingleBean(ElasticsearchClient.class);
                    assertThat(context).hasSingleBean(ChunkSearchPort.class);
                    assertThat(context).doesNotHaveBean(JdbcTemplate.class);
                    assertThat(context).doesNotHaveBean(KafkaListenerEndpointRegistry.class);
                    assertThat(context).doesNotHaveBean(Flyway.class);
                });
    }
}
