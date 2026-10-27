package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.noteweave.task.TaskOutboxEnqueuer;
import com.noteweave.task.TaskService;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * MEM-02：回执重投不能队头阻塞。
 *
 * <p>旧实现里任务不是 FAILED 时 {@code redriveTask} 抛异常、事务回滚，回执的
 * next_attempt_at 原地不动，scanDue 每轮都先捞到同一批坏回执，新回执永远轮不到。
 * 这里把两条出口都钉住：非 FAILED 明确分支，以及失败计数到达上限后转终态。
 */
class MemoryReceiptRedeliveryTest {

    private static final String WORKSPACE = "ws-1";
    private static final String TOPIC = "noteweave.memory.extraction";

    private JdbcTemplate jdbc;
    private TaskService taskService;
    private TaskOutboxEnqueuer enqueuer;
    private MemoryProcessingReceiptService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:memory-receipts-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                create table memory_processing_receipt(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    message_id varchar(36) not null,
                    task_id varchar(36),
                    status varchar(32) not null,
                    stage varchar(32) not null,
                    extract_attempts int not null default 0,
                    feedback_attempts int not null default 0,
                    delivery_generation int not null default 1,
                    feedback_applicable int not null default 1,
                    extract_method varchar(32),
                    next_attempt_at timestamp null,
                    redelivery_failures int not null default 0,
                    last_error_code varchar(100),
                    completed_at timestamp null,
                    created_at timestamp not null default current_timestamp,
                    updated_at timestamp not null default current_timestamp
                )
                """);
        jdbc.execute("create table task(id varchar(36) primary key, task_status varchar(32) not null)");
        taskService = mock(TaskService.class);
        enqueuer = mock(TaskOutboxEnqueuer.class);
        service = new MemoryProcessingReceiptService(jdbc, taskService, enqueuer,
                new DataSourceTransactionManager(dataSource), TOPIC, 50, 120, 5);
    }

    @Test
    void brokenReceiptsDoNotBlockANewerOne() {
        // 50 条任务已取消的坏回执排在队头，新回执的时间戳更晚，第一轮批次装不下它
        for (int i = 0; i < 50; i++) {
            receipt("bad-" + i, "task-bad-" + i, "CANCELLED", Instant.now().minusSeconds(600));
        }
        receipt("good", "task-good", "FAILED", Instant.now().minusSeconds(300));

        service.scanDue();

        assertThat(jdbc.queryForObject(
                "select count(*) from memory_processing_receipt where status = 'TERMINAL_FAILURE'", Integer.class))
                .isEqualTo(50);
        assertThat(jdbc.queryForObject(
                "select count(*) from memory_processing_receipt where last_error_code = 'MEMORY_RECEIPT_TASK_CANCELLED'",
                Integer.class)).isEqualTo(50);
        verifyNoInteractions(taskService, enqueuer);

        // 第二轮坏回执已经离开队列，新回执拿到机会
        service.scanDue();

        verify(taskService).redriveTask("task-good", false);
        verify(enqueuer, times(1)).enqueue(any());
        assertThat(jdbc.queryForObject(
                "select delivery_generation from memory_processing_receipt where id = 'good'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void runningTaskIsPostponedInsteadOfRedriven() {
        receipt("running", "task-running", "RUNNING", Instant.now().minusSeconds(600));

        service.scanDue();

        verify(taskService, never()).redriveTask(anyString(), anyBoolean());
        verifyNoInteractions(enqueuer);
        var row = jdbc.queryForMap(
                "select status, redelivery_failures, next_attempt_at, last_error_code from memory_processing_receipt where id = 'running'");
        assertThat(row).containsEntry("status", "RETRY_WAIT")
                .containsEntry("redelivery_failures", 1)
                .containsEntry("last_error_code", "MEMORY_RECEIPT_TASK_NOT_FAILED");
        assertThat(((Timestamp) row.get("next_attempt_at")).toInstant()).isAfter(Instant.now());
    }

    @Test
    void repeatedPostponementEndsInTerminalFailure() {
        receipt("stuck", "task-stuck", "RUNNING", Instant.now().minusSeconds(600));

        for (int round = 0; round < 5; round++) {
            service.scanDue();
            // 把下次尝试时间拨回过去，模拟扫描间隔已过
            jdbc.update("""
                    update memory_processing_receipt
                    set next_attempt_at = timestampadd(second, -10, current_timestamp) where id = 'stuck'
                    """);
        }

        var row = jdbc.queryForMap(
                "select status, redelivery_failures from memory_processing_receipt where id = 'stuck'");
        assertThat(row).containsEntry("status", "TERMINAL_FAILURE").containsEntry("redelivery_failures", 5);
    }

    @Test
    void enqueueFailureCountsAsAFailedRedeliveryAndBacksOff() {
        receipt("failing", "task-failing", "FAILED", Instant.now().minusSeconds(600));
        doThrow(new IllegalStateException("outbox down")).when(enqueuer).enqueue(any());

        service.scanDue();

        var row = jdbc.queryForMap("""
                select status, delivery_generation, redelivery_failures, next_attempt_at
                from memory_processing_receipt where id = 'failing'
                """);
        // 换代写入被回滚，回执留在 RETRY_WAIT 并后退，不会每轮都卡在队头
        assertThat(row).containsEntry("status", "RETRY_WAIT")
                .containsEntry("delivery_generation", 1)
                .containsEntry("redelivery_failures", 1);
        assertThat(((Timestamp) row.get("next_attempt_at")).toInstant()).isAfter(Instant.now());
    }

    @Test
    void completedTaskAndMissingTaskEndAsTerminalWithoutRedrive() {
        receipt("done", "task-done", "COMPLETED", Instant.now().minusSeconds(600));
        jdbc.update("""
                insert into memory_processing_receipt(id, workspace_id, message_id, task_id, status, stage,
                    next_attempt_at) values ('orphan', ?, 'msg-orphan', 'task-gone', 'RETRY_WAIT', 'EXTRACT', ?)
                """, WORKSPACE, Timestamp.from(Instant.now().minusSeconds(600)));

        service.scanDue();

        assertThat(jdbc.queryForMap(
                "select status, last_error_code from memory_processing_receipt where id = 'done'"))
                .containsEntry("status", "TERMINAL_FAILURE")
                .containsEntry("last_error_code", "MEMORY_RECEIPT_TASK_COMPLETED");
        assertThat(jdbc.queryForMap(
                "select status, last_error_code from memory_processing_receipt where id = 'orphan'"))
                .containsEntry("status", "TERMINAL_FAILURE")
                .containsEntry("last_error_code", "MEMORY_RECEIPT_TASK_MISSING");
        verifyNoInteractions(taskService, enqueuer);
    }

    private void receipt(String id, String taskId, String taskStatus, Instant nextAttemptAt) {
        jdbc.update("""
                insert into memory_processing_receipt(id, workspace_id, message_id, task_id, status, stage,
                    next_attempt_at) values (?, ?, ?, ?, 'RETRY_WAIT', 'EXTRACT', ?)
                """, id, WORKSPACE, "msg-" + id, taskId, Timestamp.from(nextAttemptAt));
        jdbc.update("insert into task(id, task_status) values (?, ?)", taskId, taskStatus);
    }
}
