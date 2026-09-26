package com.noteweave.task;

import com.noteweave.common.BusinessException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Owns task/event read SQL, including the cursor semantics used by task SSE. */
@Repository
class TaskReadRepository {
    private final JdbcTemplate jdbcTemplate;

    TaskReadRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    TaskRecord getTask(String taskId) {
        return jdbcTemplate.query("""
                select id, task_type, task_status, progress_phase, progress_message,
                       result_ref, error_message, target_type, target_id
                from task where id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("TASK_NOT_FOUND", "任务不存在");
            }
            return new TaskRecord(
                    rs.getString("id"),
                    rs.getString("task_type"),
                    rs.getString("task_status"),
                    rs.getString("progress_phase"),
                    rs.getString("progress_message"),
                    blank(rs.getString("result_ref")),
                    blank(rs.getString("error_message")),
                    blank(rs.getString("target_type")),
                    blank(rs.getString("target_id"))
            );
        }, taskId);
    }

    TaskService.TaskRef loadTaskRef(String taskId, boolean forUpdate) {
        String sql = """
                select id, workspace_id, task_type, task_status, target_type, target_id
                from task where id = ?
                """ + (forUpdate ? " for update" : "");
        return jdbcTemplate.query(sql, rs -> {
            if (!rs.next()) {
                throw new BusinessException("TASK_NOT_FOUND", "任务不存在");
            }
            return new TaskService.TaskRef(
                    rs.getString("id"),
                    rs.getString("workspace_id"),
                    rs.getString("task_type"),
                    rs.getString("task_status"),
                    rs.getString("target_type"),
                    rs.getString("target_id")
            );
        }, taskId);
    }

    TaskQuotaRow loadTaskQuota(String taskId) {
        return jdbcTemplate.query("""
                select id, workspace_id, task_type from task where id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("TASK_NOT_FOUND", "任务不存在");
            }
            return new TaskQuotaRow(
                    rs.getString("id"),
                    rs.getString("workspace_id"),
                    rs.getString("task_type")
            );
        }, taskId);
    }

    Map<String, String> loadLatestProgressPayloads(List<String> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> payloads = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select task_id, payload_json
                from (
                    select task_id, payload_json,
                           row_number() over (
                               partition by task_id
                               order by created_at desc, id desc
                           ) as row_no
                    from task_event
                    where task_id in (%s) and event_type = 'TASK_PROGRESS'
                ) latest
                where row_no = 1
                """.formatted(String.join(",", java.util.Collections.nCopies(taskIds.size(), "?"))), rs -> {
            while (rs.next()) {
                payloads.put(rs.getString("task_id"), rs.getString("payload_json"));
            }
            return null;
        }, taskIds.toArray());
        return Map.copyOf(payloads);
    }

    String loadLatestProgressPayload(String taskId) {
        return jdbcTemplate.query("""
                select payload_json
                from task_event
                where task_id = ? and event_type = 'TASK_PROGRESS'
                order by created_at desc, id desc
                limit 1
                """, rs -> rs.next() ? rs.getString("payload_json") : null, taskId);
    }

    List<TaskEventResponse> listEvents(String taskId, String afterEventId) {
        return listEvents(taskId, afterEventId, 200);
    }

    List<TaskEventResponse> listEvents(String taskId, String afterEventId, int limit) {
        String cursorId = afterEventId == null || afterEventId.isBlank() ? null : afterEventId;
        return jdbcTemplate.query("""
                select event.id, event.event_type, event.message, event.payload_json, event.created_at
                from task_event event
                left join task_event after_event
                  on after_event.id = ?
                 and after_event.task_id = event.task_id
                where event.task_id = ?
                  and (
                    ? is null
                    or event.created_at > after_event.created_at
                    or (event.created_at = after_event.created_at and event.id > after_event.id)
                  )
                order by event.created_at asc, event.id asc
                limit ?
                """, (rs, rowNum) -> new TaskEventResponse(
                rs.getString("id"),
                rs.getString("event_type"),
                blank(rs.getString("message")),
                rs.getString("payload_json"),
                instant(rs.getTimestamp("created_at"))
        ), cursorId, taskId, cursorId, limit);
    }

    TaskService.TaskStreamPoll poll(String taskId, String afterEventId) {
        String cursorId = afterEventId == null || afterEventId.isBlank() ? null : afterEventId;
        return jdbcTemplate.query("""
                select t.task_status,
                       event.id, event.event_type, event.message, event.payload_json, event.created_at
                from task t
                left join task_event after_event
                  on after_event.id = ?
                 and after_event.task_id = t.id
                left join task_event event
                  on event.task_id = t.id
                 and (
                    ? is null
                    or event.created_at > after_event.created_at
                    or (event.created_at = after_event.created_at and event.id > after_event.id)
                 )
                where t.id = ?
                order by event.created_at asc, event.id asc
                limit 100
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("TASK_NOT_FOUND", "任务不存在");
            }
            String status = rs.getString("task_status");
            List<TaskEventResponse> events = new ArrayList<>();
            do {
                String eventId = rs.getString("id");
                if (eventId != null) {
                    events.add(new TaskEventResponse(
                            eventId,
                            rs.getString("event_type"),
                            blank(rs.getString("message")),
                            rs.getString("payload_json"),
                            instant(rs.getTimestamp("created_at"))
                    ));
                }
            } while (rs.next());
            return new TaskService.TaskStreamPoll(List.copyOf(events), status);
        }, cursorId, cursorId, taskId);
    }

    String currentStatus(String taskId) {
        return jdbcTemplate.query("""
                select task_status from task where id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("TASK_NOT_FOUND", "任务不存在");
            }
            return rs.getString("task_status");
        }, taskId);
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? Instant.now() : value.toInstant();
    }
}

record TaskRecord(
        String id,
        String taskType,
        String taskStatus,
        String progressPhase,
        String progressMessage,
        String resultRef,
        String errorMessage,
        String targetType,
        String targetId
) {
}

record TaskQuotaRow(String id, String workspaceId, String taskType) {
}
