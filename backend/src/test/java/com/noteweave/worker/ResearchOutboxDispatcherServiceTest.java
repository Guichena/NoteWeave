package com.noteweave.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.noteweave.task.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ResearchOutboxDispatcherServiceTest {

    private JdbcTemplate jdbcTemplate;
    private ResearchOutboxPublisher publisher;
    private TaskService taskService;
    private ResearchOutboxDispatcherService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:research-outbox-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table task_outbox(
                    id varchar(36) primary key,
                    task_id varchar(36),
                    topic varchar(160),
                    message_key varchar(160),
                    payload_json longtext,
                    status varchar(32),
                    attempt_count int default 0,
                    claimed_at timestamp,
                    lease_owner varchar(160),
                    lease_until timestamp,
                    next_attempt_at timestamp,
                    dead_lettered_at timestamp,
                    last_error varchar(1000),
                    sent_at timestamp,
                    created_at timestamp default current_timestamp
                )
                """);
        publisher = mock(ResearchOutboxPublisher.class);
        taskService = mock(TaskService.class);
        service = new ResearchOutboxDispatcherService(jdbcTemplate, publisher, taskService);
    }

    @Test
    void readyRunShouldBeClaimedPublishedAndMarkedSent() {
        insert("outbox-1", 0);

        ResearchOutboxDispatchResponse response = service.dispatchReadyResearchRuns(5);

        assertThat(response.dispatchedCount()).isEqualTo(1);
        assertThat(status("outbox-1")).isEqualTo("SENT");
        assertThat(jdbcTemplate.queryForObject(
                "select attempt_count from task_outbox where id = 'outbox-1'", Integer.class)).isEqualTo(1);
        verify(publisher).publish("noteweave.research.run", "run-1", "{}");
    }

    @Test
    void exhaustedPublishShouldDeadLetterAndFailTask() {
        insert("outbox-2", 4);
        doThrow(new IllegalStateException("broker unavailable"))
                .when(publisher).publish("noteweave.research.run", "run-1", "{}");

        ResearchOutboxDispatchResponse response = service.dispatchReadyResearchRuns(5);

        assertThat(response.dispatchedCount()).isZero();
        assertThat(status("outbox-2")).isEqualTo("DEAD_LETTER");
        verify(taskService).failTask("task-1", "OUTBOX_DEAD_LETTER",
                "Research Kafka 投递耗尽，已进入死信", "RESEARCH_DISPATCH_EXHAUSTED", true);
    }

    private void insert(String id, int attempts) {
        jdbcTemplate.update("""
                insert into task_outbox(
                    id, task_id, topic, message_key, payload_json, status, attempt_count
                ) values (?, 'task-1', 'noteweave.research.run', 'run-1', '{}', 'READY', ?)
                """, id, attempts);
    }

    private String status(String id) {
        return jdbcTemplate.queryForObject(
                "select status from task_outbox where id = ?", String.class, id);
    }
}
