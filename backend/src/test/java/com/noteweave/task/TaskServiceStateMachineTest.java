package com.noteweave.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.util.Map;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class TaskServiceStateMachineTest {

    private JdbcTemplate jdbcTemplate;
    private TaskService taskService;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:task-state-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table task (
                    id varchar(64) primary key,
                    workspace_id varchar(64) not null,
                    task_type varchar(64) not null,
                    task_status varchar(32) not null,
                    target_type varchar(64),
                    target_id varchar(64),
                    progress_phase varchar(128),
                    progress_message varchar(512),
                    result_ref varchar(512),
                    error_message varchar(1024),
                    created_by varchar(80) not null,
                    updated_by varchar(80) not null,
                    created_at timestamp default current_timestamp,
                    updated_at timestamp default current_timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table task_event (
                    id varchar(64) primary key,
                    task_id varchar(64) not null,
                    event_type varchar(64) not null,
                    message varchar(512),
                    payload_json clob,
                    created_at timestamp default current_timestamp
                )
                """);
        meterRegistry = new SimpleMeterRegistry();
        taskService = new TaskService(jdbcTemplate, new ObjectMapper(), meterRegistry);
    }

    @Test
    void completedTaskCannotReturnToRunning() {
        String taskId = createRunningTask();
        taskService.completeTask(taskId, "DONE", "done", "result-1");

        assertThatThrownBy(() -> taskService.recordProgress(
                taskId,
                "RETRYING",
                "late progress",
                90,
                Map.of(),
                Map.of()
        ))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).code())
                .isEqualTo("TASK_STATE_TRANSITION_INVALID");

        assertThat(status(taskId)).isEqualTo("COMPLETED");
        assertThat(updatedBy(taskId)).isEqualTo("SYSTEM:TASK");
        assertThat(eventCount(taskId, "TASK_PROGRESS")).isZero();
        assertThat(meterRegistry.get("noteweave.task.invalid_transition")
                .tag("from", "COMPLETED").tag("to", "RUNNING").counter().count()).isEqualTo(1.0);
    }

    @Test
    void heartbeatCannotMutateTerminalTask() {
        String taskId = createRunningTask();
        taskService.failTask(taskId, "FAILED", "failed", "WORKER_FAILED", true);

        assertThatThrownBy(() -> taskService.recordHeartbeat(
                taskId,
                "RUNNING_AGAIN",
                "late heartbeat",
                Map.of()
        ))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).code())
                .isEqualTo("TASK_STATE_TRANSITION_INVALID");

        assertThat(phase(taskId)).isEqualTo("FAILED");
        assertThat(eventCount(taskId, "TASK_HEARTBEAT")).isZero();
    }

    @Test
    void duplicateCompletionIsIdempotentAndDoesNotDuplicateEvent() {
        String taskId = createRunningTask();

        taskService.completeTask(taskId, "DONE", "done", "result-1");
        taskService.completeTask(taskId, "DONE", "done", "result-1");

        assertThat(status(taskId)).isEqualTo("COMPLETED");
        assertThat(eventCount(taskId, "TASK_COMPLETED")).isEqualTo(1);
    }

    private String createRunningTask() {
        String taskId = taskService.createTask(
                "workspace-1",
                "TEST",
                "SOURCE",
                "source-1",
                "QUEUED",
                "queued"
        );
        taskService.startTask(taskId);
        return taskId;
    }

    private String status(String taskId) {
        return jdbcTemplate.queryForObject(
                "select task_status from task where id = ?",
                String.class,
                taskId
        );
    }

    private String phase(String taskId) {
        return jdbcTemplate.queryForObject(
                "select progress_phase from task where id = ?",
                String.class,
                taskId
        );
    }

    private String updatedBy(String taskId) {
        return jdbcTemplate.queryForObject(
                "select updated_by from task where id = ?",
                String.class,
                taskId
        );
    }

    private int eventCount(String taskId, String eventType) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from task_event where task_id = ? and event_type = ?",
                Integer.class,
                taskId,
                eventType
        );
        return count == null ? 0 : count;
    }
}
