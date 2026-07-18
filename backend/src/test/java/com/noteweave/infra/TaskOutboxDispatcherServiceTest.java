package com.noteweave.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.config.NoteWeaveProperties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;

class TaskOutboxDispatcherServiceTest {

    private JdbcTemplate jdbcTemplate;
    private KafkaTemplate<String, String> kafkaTemplate;
    private NoteWeaveProperties properties;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:outbox-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table task_outbox (
                    id varchar(36) primary key, task_id varchar(36), topic varchar(160) not null,
                    message_key varchar(160) not null, payload_json clob not null, status varchar(32) not null,
                    attempt_count int not null default 0, last_error varchar(1000), claimed_at timestamp,
                    next_attempt_at timestamp, lease_owner varchar(128), lease_until timestamp,
                    dead_lettered_at timestamp, sent_at timestamp, created_at timestamp default current_timestamp
                )
                """);
        kafkaTemplate = mock(KafkaTemplate.class);
        properties = new NoteWeaveProperties(null, null, null,
                new NoteWeaveProperties.Kafka(true, new NoteWeaveProperties.Topics(
                        "noteweave.source.parse", "noteweave.source.chunk", "noteweave.source.index",
                        "noteweave.wiki.ingest", "noteweave.wiki.retract", "noteweave.generated.ingest",
                        "noteweave.conversation.summary")),
                null, null);
    }

    @Test
    void competingDispatchersShouldPublishReadyMessageOnce() throws Exception {
        insertOutbox("outbox-1", 0);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        TaskOutboxDispatcherService first = new TaskOutboxDispatcherService(jdbcTemplate, kafkaTemplate, properties, mock(com.noteweave.task.TaskService.class));
        TaskOutboxDispatcherService second = new TaskOutboxDispatcherService(jdbcTemplate, kafkaTemplate, properties, mock(com.noteweave.task.TaskService.class));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(first::dispatchReadyMessages);
            var two = pool.submit(second::dispatchReadyMessages);
            one.get();
            two.get();
        } finally {
            pool.shutdownNow();
        }

        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
        assertThat(status("outbox-1")).isEqualTo("SENT");
    }

    @Test
    void fifthFailureShouldDeadLetterMessage() {
        insertOutbox("outbox-2", 4);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        new TaskOutboxDispatcherService(jdbcTemplate, kafkaTemplate, properties,
                mock(com.noteweave.task.TaskService.class), meterRegistry).dispatchReadyMessages();

        assertThat(status("outbox-2")).isEqualTo("DEAD_LETTER");
        assertThat(jdbcTemplate.queryForObject(
                "select attempt_count from task_outbox where id = ?", Integer.class, "outbox-2"))
                .isEqualTo(5);
        assertThat(meterRegistry.get("noteweave.outbox.dispatch.dead_letter")
                .tag("topic", "noteweave.source.parse").counter().count()).isEqualTo(1.0);
    }

    private void insertOutbox(String id, int attempts) {
        jdbcTemplate.update("""
                insert into task_outbox(id, task_id, topic, message_key, payload_json, status, attempt_count)
                values (?, 'task-1', 'noteweave.source.parse', 'source-1', '{}', 'READY', ?)
                """, id, attempts);
    }

    private String status(String id) {
        return jdbcTemplate.queryForObject(
                "select status from task_outbox where id = ?", String.class, id);
    }
}
