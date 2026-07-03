package com.noteweave.task;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskService {

    private final JdbcTemplate jdbcTemplate;

    public TaskService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public String createTask(String workspaceId, String taskType, String targetType, String targetId, String phase, String message) {
        String taskId = Ids.newId();
        jdbcTemplate.update("""
                insert into task(id, workspace_id, task_type, task_status, target_type, target_id, progress_phase, progress_message)
                values (?, ?, ?, 'PENDING', ?, ?, ?, ?)
                """, taskId, workspaceId, taskType, targetType, targetId, phase, message);
        jdbcTemplate.update("""
                insert into task_event(id, task_id, event_type, message)
                values (?, ?, 'TASK_CREATED', ?)
                """, Ids.newId(), taskId, message);
        return taskId;
    }

    @Transactional
    public void completeTask(String taskId, String phase, String message, String resultRef) {
        jdbcTemplate.update("""
                update task
                set task_status = 'COMPLETED', progress_phase = ?, progress_message = ?, result_ref = ?, updated_at = current_timestamp
                where id = ?
                """, phase, message, resultRef, taskId);
        jdbcTemplate.update("""
                insert into task_event(id, task_id, event_type, message)
                values (?, ?, 'TASK_COMPLETED', ?)
                """, Ids.newId(), taskId, message);
    }

    public TaskResponse getTask(String taskId) {
        return jdbcTemplate.query("""
                select id, task_type, task_status, progress_phase, progress_message, result_ref, error_message, target_type, target_id
                from task where id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("TASK_NOT_FOUND", "任务不存在");
            }
            return new TaskResponse(
                    rs.getString("id"),
                    rs.getString("task_type"),
                    rs.getString("task_status"),
                    rs.getString("progress_phase"),
                    rs.getString("progress_message"),
                    rs.getString("result_ref") == null ? "" : rs.getString("result_ref"),
                    rs.getString("error_message") == null ? "" : rs.getString("error_message"),
                    rs.getString("target_type") == null ? "" : rs.getString("target_type"),
                    rs.getString("target_id") == null ? "" : rs.getString("target_id")
            );
        }, taskId);
    }
}
