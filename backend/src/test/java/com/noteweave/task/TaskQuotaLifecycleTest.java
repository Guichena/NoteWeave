package com.noteweave.task;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.quota.WorkloadQuotaService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class TaskQuotaLifecycleTest {

    private JdbcTemplate jdbcTemplate;
    private WorkloadQuotaService quotaService;
    private TaskService taskService;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:task-quota-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbcTemplate.execute("""
                create table task (
                    id varchar(64) primary key,
                    workspace_id varchar(64) not null,
                    task_type varchar(64) not null,
                    task_status varchar(32) not null,
                    target_type varchar(64), target_id varchar(64),
                    progress_phase varchar(128), progress_message varchar(512),
                    result_ref varchar(512), error_message varchar(1024),
                    created_by varchar(80) not null, updated_by varchar(80) not null,
                    created_at timestamp default current_timestamp,
                    updated_at timestamp default current_timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table task_event (
                    id varchar(64) primary key,
                    task_id varchar(64) not null,
                    event_type varchar(64) not null,
                    message varchar(512), payload_json clob,
                    created_at timestamp default current_timestamp
                )
                """);
        quotaService = mock(WorkloadQuotaService.class);
        taskService = new TaskService(
                jdbcTemplate, new ObjectMapper(), new SimpleMeterRegistry(), null, quotaService);
    }

    @Test
    void longTaskShouldAcquireRenewAndReleaseLease() {
        String taskId = taskService.createTask(
                "workspace", "RESEARCH_RUN", "RESEARCH_RUN", "run-1", "QUEUED", "queued");
        taskService.startTask(taskId);
        taskService.recordHeartbeat(taskId, "READING", "heartbeat", Map.of());
        taskService.completeTask(taskId, "DONE", "done", "run-1");

        InOrder order = inOrder(quotaService);
        order.verify(quotaService).requireRate("workspace", "research");
        order.verify(quotaService).acquireLease("workspace", "research", taskId);
        order.verify(quotaService).renewLease("workspace", "research", taskId);
        order.verify(quotaService).releaseLease("workspace", "research", taskId);
    }

    @Test
    void failedTaskShouldReleaseAndRedriveShouldReacquire() {
        String taskId = taskService.createTask(
                "workspace", "ARTIFACT_JOB", "ARTIFACT_JOB", "job-1", "QUEUED", "queued");
        taskService.startTask(taskId);
        taskService.failTask(taskId, "FAILED", "failed", "WORKER_FAILED", true);
        taskService.redriveTask(taskId, false);

        verify(quotaService, org.mockito.Mockito.times(2))
                .requireRate("workspace", "artifact");
        verify(quotaService, org.mockito.Mockito.times(2))
                .acquireLease("workspace", "artifact", taskId);
        verify(quotaService).releaseLease("workspace", "artifact", taskId);
    }

    @Test
    void internalTaskShouldNotUseLongWorkloadQuota() {
        String taskId = taskService.createTask(
                "workspace", "SOURCE_PARSE", "SOURCE", "source-1", "PARSING", "parse");
        taskService.startTask(taskId);
        taskService.completeTask(taskId, "INDEXED", "done", "source-1");

        verify(quotaService, org.mockito.Mockito.never())
                .requireRate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void duplicateCompletionMustNotDoubleRelease() {
        String taskId = taskService.createTask(
                "workspace", "RESEARCH_RUN", "RESEARCH_RUN", "run-1", "QUEUED", "queued");
        taskService.startTask(taskId);
        taskService.completeTask(taskId, "DONE", "done", "run-1");
        taskService.completeTask(taskId, "DONE", "done", "run-1");

        verify(quotaService).releaseLease("workspace", "research", taskId);
        assertThatThrownBy(() -> taskService.recordHeartbeat(taskId, "LATE", "late", Map.of()))
                .isInstanceOf(com.noteweave.common.BusinessException.class);
    }

    @Test
    void failedRedriveTransitionShouldReleaseLeaseAfterRollback() {
        jdbcTemplate.update("""
                insert into task(
                    id, workspace_id, task_type, task_status, target_type, target_id,
                    progress_phase, progress_message, created_by, updated_by
                ) values ('task-active', 'workspace', 'RESEARCH_RUN', 'RUNNING',
                    'RESEARCH_RUN', 'run-1', 'RUNNING', 'active', 'system', 'system')
                """);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                ignored -> taskService.redriveTask("task-active", false)))
                .isInstanceOf(com.noteweave.common.BusinessException.class);

        InOrder order = inOrder(quotaService);
        order.verify(quotaService).requireRate("workspace", "research");
        order.verify(quotaService).acquireLease("workspace", "research", "task-active");
        order.verify(quotaService).releaseLease("workspace", "research", "task-active");
    }
}
