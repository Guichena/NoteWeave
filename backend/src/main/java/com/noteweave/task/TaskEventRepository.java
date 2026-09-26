package com.noteweave.task;

import com.noteweave.common.Ids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists task events inside the transaction owned by TaskService. */
@Repository
class TaskEventRepository {
    private final JdbcTemplate jdbcTemplate;

    TaskEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void append(String taskId, String eventType, String message) {
        append(taskId, eventType, message, null);
    }

    void append(String taskId, String eventType, String message, String payloadJson) {
        jdbcTemplate.update("""
                insert into task_event(id, task_id, event_type, message, payload_json)
                values (?, ?, ?, ?, ?)
                """, Ids.newId(), taskId, eventType, message, payloadJson);
    }
}
