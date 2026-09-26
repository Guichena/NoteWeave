package com.noteweave.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class TaskReadRepositoryTest {

    @Test
    void batchWaitContextQueryShouldReadOnlyTheLatestProgressPerTask() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:task-read-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""));
        jdbcTemplate.execute("""
                create table task_event (
                    id varchar(64) primary key,
                    task_id varchar(64) not null,
                    event_type varchar(64) not null,
                    message varchar(512),
                    payload_json clob,
                    created_at timestamp not null
                )
                """);
        jdbcTemplate.update(
                "insert into task_event(id, task_id, event_type, payload_json, created_at) values (?, ?, ?, ?, ?)",
                "a-1", "task-a", "TASK_PROGRESS", "{\"attempt\":1}", "2026-08-06 10:00:00");
        jdbcTemplate.update(
                "insert into task_event(id, task_id, event_type, payload_json, created_at) values (?, ?, ?, ?, ?)",
                "a-2", "task-a", "TASK_PROGRESS", "{\"attempt\":2}", "2026-08-06 10:01:00");
        jdbcTemplate.update(
                "insert into task_event(id, task_id, event_type, payload_json, created_at) values (?, ?, ?, ?, ?)",
                "b-1", "task-b", "TASK_PROGRESS", "{\"attempt\":7}", "2026-08-06 10:00:30");
        jdbcTemplate.update(
                "insert into task_event(id, task_id, event_type, payload_json, created_at) values (?, ?, ?, ?, ?)",
                "a-3", "task-a", "TASK_HEARTBEAT", "{\"attempt\":99}", "2026-08-06 10:02:00");

        Map<String, String> payloads = new TaskReadRepository(jdbcTemplate)
                .loadLatestProgressPayloads(List.of("task-a", "task-b"));

        assertThat(payloads).containsExactlyInAnyOrderEntriesOf(Map.of(
                "task-a", "{\"attempt\":2}",
                "task-b", "{\"attempt\":7}"
        ));
    }
}
