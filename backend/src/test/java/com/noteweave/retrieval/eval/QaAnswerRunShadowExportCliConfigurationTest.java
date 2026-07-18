package com.noteweave.retrieval.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.retrieval.QaRetrievalStrategyProfile;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class QaAnswerRunShadowExportCliConfigurationTest {

    @TempDir
    Path tempDir;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues(
                    "spring.profiles.active=qa-answer-run-shadow-export-cli",
                    "spring.datasource.url=jdbc:h2:mem:qa-shadow-cli;DB_CLOSE_DELAY=-1",
                    "spring.datasource.driver-class-name=org.h2.Driver",
                    "spring.datasource.username=sa",
                    "spring.datasource.password=",
                    "spring.jackson.property-naming-strategy=SNAKE_CASE")
            .withUserConfiguration(QaAnswerRunShadowExportCliConfiguration.class);

    @Test
    void shouldLoadOnlyJdbcShadowExportRuntime() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(DataSource.class);
            assertThat(context).hasSingleBean(JdbcTemplate.class);
            assertThat(context).hasSingleBean(ObjectMapper.class);
            assertThat(context).hasSingleBean(RetrievalSnapshotSanitizer.class);
            assertThat(context).hasSingleBean(RetrievalExecutionShadowExporter.class);
            assertThat(context).hasSingleBean(QaAnswerRunShadowExportService.class);
            assertThat(AopUtils.isAopProxy(
                    context.getBean(QaAnswerRunShadowExportService.class))).isTrue();

            assertThat(context).doesNotHaveBean(QaGoldAnnotationDraftCapture.class);
            assertThat(context).doesNotHaveBean(ElasticsearchClient.class);
            assertThat(context).doesNotHaveBean(KafkaListenerEndpointRegistry.class);
            assertThat(context).doesNotHaveBean(RedisConnectionFactory.class);
            assertThat(context).doesNotHaveBean(Flyway.class);
        });
    }

    @Test
    void shouldOpenReadOnlyTransactionBeforeExportQueries() throws Exception {
        AtomicBoolean transactionObserved = TransactionProbeConfiguration.TRANSACTION_OBSERVED;
        transactionObserved.set(false);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path annotationPath = tempDir.resolve("annotation.json");
        Path runMapPath = tempDir.resolve("run-map.json");
        mapper.writeValue(annotationPath.toFile(), new QaGoldAnnotationRequest(
                QaGoldAnnotationDraftCapture.REQUEST_SCHEMA_VERSION,
                "dataset",
                12,
                List.of(new QaGoldAnnotationRequest.CaseRequest(
                        "case-1", "workspace-1", "query", List.of("source-1"), 6))));
        mapper.writeValue(runMapPath.toFile(), new QaAnswerRunShadowExportRequest(
                QaAnswerRunShadowExportRequest.SCHEMA_VERSION,
                "snapshot",
                QaRetrievalStrategyProfile.V2.profileVersion(),
                List.of(new QaAnswerRunShadowExportRequest.CaseRequest("case-1", "run-1"))));

        contextRunner.withUserConfiguration(TransactionProbeConfiguration.class)
                .run(context -> {
                    QaAnswerRunShadowExportService service =
                            context.getBean(QaAnswerRunShadowExportService.class);
                    assertThatThrownBy(() -> service.exportAndWrite(
                            annotationPath,
                            runMapPath,
                            tempDir.resolve("shadow.json"),
                            "0123456789abcdef"))
                            .isInstanceOf(TransactionProbeStop.class);
                    assertThat(transactionObserved).isTrue();
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TransactionProbeConfiguration {
        private static final AtomicBoolean TRANSACTION_OBSERVED = new AtomicBoolean();

        @Bean
        @Primary
        JdbcTemplate transactionProbeJdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource) {
                @Override
                public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                    TRANSACTION_OBSERVED.set(
                            TransactionSynchronizationManager.isActualTransactionActive()
                                    && TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                    throw new TransactionProbeStop();
                }
            };
        }
    }

    static final class TransactionProbeStop extends RuntimeException {
    }
}
