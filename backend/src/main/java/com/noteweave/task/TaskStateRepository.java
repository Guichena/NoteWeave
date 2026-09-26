package com.noteweave.task;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Owns conditional task-row writes; TaskService remains the state-machine owner. */
@Repository
class TaskStateRepository {
    private final JdbcTemplate jdbcTemplate;

    TaskStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void insertTask(
            String taskId,
            String workspaceId,
            String taskType,
            String targetType,
            String targetId,
            String phase,
            String message,
            String actor
    ) {
        jdbcTemplate.update("""
                insert into task(
                    id, workspace_id, task_type, task_status, target_type, target_id, progress_phase, progress_message,
                    created_by, updated_by
                ) values (?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?)
                """, taskId, workspaceId, taskType, targetType, targetId, phase, message, actor, actor);
    }

    int start(String taskId, String actor) {
        return jdbcTemplate.update("""
                update task
                set task_status = 'RUNNING', updated_by = ?, updated_at = current_timestamp
                where id = ? and task_status = 'PENDING'
                """, actor, taskId);
    }

    int cancel(String taskId, String phase, String message, String resultRef, String actor) {
        return jdbcTemplate.update("""
                update task
                set task_status = 'CANCELLED', progress_phase = ?, progress_message = ?, result_ref = ?,
                    updated_by = ?, updated_at = current_timestamp
                where id = ? and task_status in ('PENDING', 'RUNNING', 'WAITING')
                """, phase, message, resultRef, actor, taskId);
    }

    int complete(String taskId, String phase, String message, String resultRef, String actor) {
        return jdbcTemplate.update("""
                update task
                set task_status = 'COMPLETED', progress_phase = ?, progress_message = ?, result_ref = ?,
                    updated_by = ?, updated_at = current_timestamp
                where id = ? and task_status in ('RUNNING', 'WAITING')
                """, phase, message, resultRef, actor, taskId);
    }

    int updateHeartbeatPhase(String taskId, String phase, String actor) {
        return jdbcTemplate.update("""
                update task
                set progress_phase = ?, updated_by = ?, updated_at = current_timestamp
                where id = ? and task_status in ('PENDING', 'RUNNING', 'WAITING')
                """, phase, actor, taskId);
    }

    int recordProgress(String taskId, String phase, String message, String actor) {
        return jdbcTemplate.update("""
                update task
                set task_status = 'RUNNING',
                    progress_phase = ?,
                    progress_message = ?,
                    updated_by = ?,
                    updated_at = current_timestamp
                where id = ? and task_status in ('RUNNING', 'WAITING')
                """, phase, message, actor, taskId);
    }

    int recordWaiting(String taskId, String phase, String message, String actor) {
        return jdbcTemplate.update("""
                update task
                set task_status = 'WAITING',
                    progress_phase = ?,
                    progress_message = ?,
                    updated_by = ?,
                    updated_at = current_timestamp
                where id = ? and task_status = 'RUNNING'
                """, phase, message, actor, taskId);
    }

    int fail(String taskId, String phase, String message, String errorCode, String actor) {
        return jdbcTemplate.update("""
                update task
                set task_status = 'FAILED',
                    progress_phase = ?,
                    progress_message = ?,
                    error_message = ?,
                    updated_by = ?,
                    updated_at = current_timestamp
                where id = ? and task_status in ('PENDING', 'RUNNING', 'WAITING')
                """, phase, message, errorCode + ": " + message, actor, taskId);
    }

    int redrive(String taskId, String targetStatus, String progressMessage, String actor) {
        return jdbcTemplate.update("""
                update task set task_status = ?, progress_phase = 'REDRIVEN',
                    progress_message = ?, error_message = null,
                    updated_by = ?, updated_at = current_timestamp
                where id = ? and task_status = 'FAILED'
                """, targetStatus, progressMessage, actor, taskId);
    }
}
