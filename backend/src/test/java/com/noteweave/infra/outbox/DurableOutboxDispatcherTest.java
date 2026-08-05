package com.noteweave.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DurableOutboxDispatcherTest {

    private JdbcTemplate jdbcTemplate;
    private DurableOutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:durable-outbox-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table task_outbox (
                    id varchar(36) primary key, task_id varchar(36), topic varchar(160) not null,
                    message_key varchar(160) not null, payload_json clob not null,
                    status varchar(32) not null, attempt_count int not null default 0,
                    last_error varchar(1000), claimed_at timestamp, next_attempt_at timestamp,
                    lease_owner varchar(128), lease_until timestamp, dead_lettered_at timestamp,
                    sent_at timestamp, created_at timestamp default current_timestamp
                )
                """);
        dispatcher = new DurableOutboxDispatcher(jdbcTemplate, new SimpleMeterRegistry());
    }

    @Test
    void staleArtifactDeliveryCannotAcknowledgeNewClaim() {
        jdbcTemplate.update("""
                insert into task_outbox(
                    id, task_id, topic, message_key, payload_json, status
                ) values ('outbox-1', 'task-1', 'noteweave.artifact.job', 'artifact-1', '{}', 'READY')
                """);
        List<String> deliveryTokens = new ArrayList<>();
        DurableOutboxDispatcher.TopicPolicy policy = new DurableOutboxDispatcher.TopicPolicy(
                List.of("noteweave.artifact.job"),
                Duration.ofMinutes(70),
                DurableOutboxDispatcher.CompletionMode.LEASED_UNTIL_CALLBACK,
                20
        );

        assertThat(dispatcher.dispatchTaskMessages(
                policy, 1, message -> deliveryTokens.add(message.deliveryToken()), null
        ).publishedCount()).isEqualTo(1);
        jdbcTemplate.update(
                "update task_outbox set lease_until = ? where id = 'outbox-1'",
                Timestamp.from(Instant.now().minusSeconds(1))
        );
        assertThat(dispatcher.dispatchTaskMessages(
                policy, 1, message -> deliveryTokens.add(message.deliveryToken()), null
        ).publishedCount()).isEqualTo(1);

        assertThat(deliveryTokens).hasSize(2);
        assertThat(deliveryTokens.get(1)).isNotEqualTo(deliveryTokens.get(0));
        assertThat(dispatcher.acknowledgeTaskMessage(
                "noteweave.artifact.job", "task-1", deliveryTokens.get(0)
        )).isFalse();
        assertThat(dispatcher.acknowledgeTaskMessage(
                "noteweave.artifact.job", "task-1", deliveryTokens.get(1)
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select status from task_outbox where id = 'outbox-1'", String.class
        )).isEqualTo("SENT");
    }

    @Test
    void exhaustedPublishTransitionsToDeadLetterExactlyOnce() {
        jdbcTemplate.update("""
                insert into task_outbox(
                    id, task_id, topic, message_key, payload_json, status, attempt_count
                ) values ('outbox-2', 'task-2', 'noteweave.source.parse', 'source-2', '{}', 'READY', 4)
                """);
        List<String> deadLettered = new ArrayList<>();

        DurableOutboxDispatcher.DispatchResult result = dispatcher.dispatchTaskMessages(
                new DurableOutboxDispatcher.TopicPolicy(
                        List.of("noteweave.source.parse"),
                        Duration.ofMinutes(1),
                        DurableOutboxDispatcher.CompletionMode.SENT,
                        50
                ),
                50,
                message -> { throw new IllegalStateException("broker unavailable"); },
                message -> deadLettered.add(message.outboxId())
        );

        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.deadLetteredCount()).isEqualTo(1);
        assertThat(deadLettered).containsExactly("outbox-2");
        assertThat(jdbcTemplate.queryForObject(
                "select status from task_outbox where id = 'outbox-2'", String.class
        )).isEqualTo("DEAD_LETTER");
        assertThat(dispatcher.dispatchTaskMessages(
                new DurableOutboxDispatcher.TopicPolicy(
                        List.of("noteweave.source.parse"),
                        Duration.ofMinutes(1),
                        DurableOutboxDispatcher.CompletionMode.SENT,
                        50
                ),
                50,
                message -> { throw new AssertionError("dead letter must not be republished"); },
                null
        ).publishedCount()).isZero();
    }

    @Test
    void exhaustedExpiredCallbackLeaseTransitionsToDeadLetterExactlyOnce() {
        jdbcTemplate.update("""
                insert into task_outbox(
                    id, task_id, topic, message_key, payload_json, status, attempt_count,
                    lease_owner, lease_until
                ) values (
                    'outbox-3', 'task-3', 'noteweave.artifact.job', 'artifact-3', '{}',
                    'PROCESSING', 5, 'stale-delivery-token', ?
                )
                """, Timestamp.from(Instant.now().minusSeconds(1)));
        List<String> deadLettered = new ArrayList<>();
        DurableOutboxDispatcher.TopicPolicy policy = new DurableOutboxDispatcher.TopicPolicy(
                List.of("noteweave.artifact.job"),
                Duration.ofMinutes(70),
                DurableOutboxDispatcher.CompletionMode.LEASED_UNTIL_CALLBACK,
                20
        );

        DurableOutboxDispatcher.DispatchResult first = dispatcher.dispatchTaskMessages(
                policy,
                20,
                message -> { throw new AssertionError("exhausted callback lease must not be republished"); },
                message -> deadLettered.add(message.outboxId())
        );

        assertThat(first.publishedCount()).isZero();
        assertThat(first.failedCount()).isEqualTo(1);
        assertThat(first.deadLetteredCount()).isEqualTo(1);
        assertThat(deadLettered).containsExactly("outbox-3");
        assertThat(jdbcTemplate.queryForObject(
                "select status from task_outbox where id = 'outbox-3'", String.class
        )).isEqualTo("DEAD_LETTER");
        assertThat(jdbcTemplate.queryForObject(
                "select last_error from task_outbox where id = 'outbox-3'", String.class
        )).contains("callback lease expired");

        DurableOutboxDispatcher.DispatchResult replay = dispatcher.dispatchTaskMessages(
                policy,
                20,
                message -> { throw new AssertionError("dead letter must not be republished"); },
                message -> deadLettered.add(message.outboxId())
        );
        assertThat(replay.deadLetteredCount()).isZero();
        assertThat(deadLettered).containsExactly("outbox-3");
    }
}
