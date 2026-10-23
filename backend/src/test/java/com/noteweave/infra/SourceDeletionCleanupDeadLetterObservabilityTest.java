package com.noteweave.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.retrieval.index.RetrievalProjectionWriter;
import com.noteweave.storage.ObjectStorage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 死信是清理链路的终点，这里验证任务落死信时既有可检索日志，也有计数器。
 */
class SourceDeletionCleanupDeadLetterObservabilityTest {

    private static final String METRIC = "noteweave.source.cleanup.dead_letter";

    @Test
    void expiredExhaustedLeaseShouldLogAllFieldsAndCountDeadLetter() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("expired");
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SourceDeletionCleanupListener listener = new SourceDeletionCleanupListener(
                jdbcTemplate, mock(ObjectStorage.class), emptyProjectionWriter(),
                properties(false), meterRegistry);
        jdbcTemplate.update("""
                insert into source_cleanup_task(
                    id, workspace_id, source_id, cleanup_type, bucket_name, object_key,
                    object_key_sha256, status, attempt_count, lease_owner, lease_until, last_error
                ) values ('cleanup-expired', 'workspace-1', 'source-1', 'SOURCE_OBJECT',
                    'noteweave-source', 'workspace/workspace-1/file.txt',
                    '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
                    'PROCESSING', 5, 'dead-worker', timestampadd(second, -1, current_timestamp),
                    'storage unavailable')
                """);

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            listener.retryCleanupTasks();
        } finally {
            detachAppender(appender);
        }

        assertThat(jdbcTemplate.queryForMap(
                "select status, attempt_count, last_error from source_cleanup_task"))
                .containsEntry("STATUS", "DEAD_LETTER")
                .containsEntry("ATTEMPT_COUNT", 5)
                .containsEntry("LAST_ERROR", "storage unavailable");
        assertThat(errorMessages(appender)).singleElement().satisfies(message -> assertThat(message)
                .contains("taskId=cleanup-expired")
                .contains("workspaceId=workspace-1")
                .contains("sourceId=source-1")
                .contains("cleanupType=SOURCE_OBJECT")
                .contains("projectionType=-")
                .contains("attemptCount=5")
                .contains("lastError=storage unavailable"));
        assertThat(meterRegistry.get(METRIC).tag("cleanup_type", "SOURCE_OBJECT").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.find(METRIC).tag("cleanup_type", "RETRIEVAL_PROJECTION").counter())
                .isNull();
    }

    @Test
    void exhaustedRetryShouldLogProjectionTypeAndCountDeadLetter() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("exhausted");
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SourceDeletionCleanupListener listener = new SourceDeletionCleanupListener(
                jdbcTemplate, mock(ObjectStorage.class), emptyProjectionWriter(),
                properties(false), meterRegistry);
        jdbcTemplate.update("""
                insert into source_cleanup_task(
                    id, workspace_id, source_id, cleanup_type, projection_type, status,
                    attempt_count, next_attempt_at
                ) values ('cleanup-projection', 'workspace-1', 'source-1', 'RETRIEVAL_PROJECTION',
                    'QA_CHUNK', 'READY', 4, current_timestamp)
                """);

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            listener.retryCleanupTasks();
        } finally {
            detachAppender(appender);
        }

        assertThat(jdbcTemplate.queryForMap(
                "select status, attempt_count, last_error from source_cleanup_task"))
                .containsEntry("STATUS", "DEAD_LETTER")
                .containsEntry("ATTEMPT_COUNT", 5)
                .containsEntry("LAST_ERROR", "Retrieval projection writer is unavailable");
        assertThat(errorMessages(appender)).singleElement().satisfies(message -> assertThat(message)
                .contains("taskId=cleanup-projection")
                .contains("cleanupType=RETRIEVAL_PROJECTION")
                .contains("projectionType=QA_CHUNK")
                .contains("attemptCount=5")
                .contains("lastError=Retrieval projection writer is unavailable"));
        assertThat(meterRegistry.get(METRIC).tag("cleanup_type", "RETRIEVAL_PROJECTION").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void retriedTaskShouldNotBeCountedBeforeDeadLetter() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("retry");
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ObjectStorage storage = mock(ObjectStorage.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("storage unavailable"))
                .when(storage).delete("noteweave-source", "workspace/workspace-1/file.txt");
        SourceDeletionCleanupListener listener = new SourceDeletionCleanupListener(
                jdbcTemplate, storage, emptyProjectionWriter(), properties(false), meterRegistry);
        jdbcTemplate.update("""
                insert into source_cleanup_task(
                    id, workspace_id, source_id, cleanup_type, bucket_name, object_key,
                    object_key_sha256, status, attempt_count, next_attempt_at
                ) values ('cleanup-retry', 'workspace-1', 'source-1', 'SOURCE_OBJECT',
                    'noteweave-source', 'workspace/workspace-1/file.txt',
                    '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
                    'READY', 0, current_timestamp)
                """);

        listener.retryCleanupTasks();

        assertThat(jdbcTemplate.queryForMap("select status, attempt_count from source_cleanup_task"))
                .containsEntry("STATUS", "READY")
                .containsEntry("ATTEMPT_COUNT", 1);
        assertThat(meterRegistry.find(METRIC).counter()).isNull();
    }

    @Test
    void listenerShouldStillWorkWithoutMeterRegistry() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("no-metrics");
        SourceDeletionCleanupListener listener = new SourceDeletionCleanupListener(
                jdbcTemplate, mock(ObjectStorage.class), emptyProjectionWriter(), properties(false));
        jdbcTemplate.update("""
                insert into source_cleanup_task(
                    id, workspace_id, source_id, cleanup_type, bucket_name, object_key,
                    object_key_sha256, status, attempt_count, lease_owner, lease_until
                ) values ('cleanup-no-metrics', 'workspace-1', 'source-1', 'SOURCE_OBJECT',
                    'noteweave-source', 'workspace/workspace-1/file.txt',
                    '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
                    'PROCESSING', 5, 'dead-worker', timestampadd(second, -1, current_timestamp))
                """);

        listener.retryCleanupTasks();

        assertThat(jdbcTemplate.queryForObject(
                "select status from source_cleanup_task", String.class)).isEqualTo("DEAD_LETTER");
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(SourceDeletionCleanupListener.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(SourceDeletionCleanupListener.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    private List<String> errorMessages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<RetrievalProjectionWriter> emptyProjectionWriter() {
        ObjectProvider<RetrievalProjectionWriter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private JdbcTemplate jdbcTemplate(String suffix) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:source-cleanup-dead-letter-" + suffix + "-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table source_cleanup_task (
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    source_id varchar(36) not null,
                    cleanup_type varchar(32) not null,
                    projection_type varchar(32),
                    bucket_name varchar(128),
                    object_key varchar(1000),
                    object_key_sha256 char(64),
                    status varchar(32) not null,
                    attempt_count int not null default 0,
                    next_attempt_at timestamp,
                    lease_owner varchar(128),
                    lease_until timestamp,
                    last_error varchar(1000),
                    dead_lettered_at timestamp,
                    completed_at timestamp,
                    created_at timestamp not null default current_timestamp
                )
                """);
        return jdbcTemplate;
    }

    private NoteWeaveProperties properties(boolean elasticsearchEnabled) {
        return new NoteWeaveProperties(
                null,
                null,
                null,
                null,
                new NoteWeaveProperties.Elasticsearch(
                        elasticsearchEnabled, "localhost", 9200, "http", "noteweave_chunk"),
                null
        );
    }
}
