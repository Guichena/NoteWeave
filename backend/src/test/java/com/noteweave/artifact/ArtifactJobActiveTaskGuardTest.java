package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * NR15：活动任务判定。再生成/回滚在 artifact_job 行锁内用它拒绝冲突操作——
 * 活动性按任务表状态（而非 Job 镜像状态）判定，QUEUED/RUNNING/WAITING 均算活动，
 * 终态任务与未登记任务不阻塞新操作。
 */
class ArtifactJobActiveTaskGuardTest {

    private JdbcTemplate jdbc;
    private ArtifactJobWriteRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:artifact-active-guard-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                create table artifact_job(
                    id varchar(36) primary key, workspace_id varchar(36),
                    latest_version_no int not null default 0, task_id varchar(36))
                """);
        jdbc.execute("""
                create table task(
                    generation int not null default 1,
                    id varchar(36) primary key, task_status varchar(32))
                """);
        repository = new ArtifactJobWriteRepository(jdbc);
    }

    private void seed(String jobId, String taskId, String taskStatus) {
        jdbc.update("insert into artifact_job(id, workspace_id, task_id) values (?, 'ws-1', ?)",
                jobId, taskId);
        if (taskId != null) {
            jdbc.update("insert into task(id, task_status) values (?, ?)", taskId, taskStatus);
        }
    }

    @Test
    void runningWaitingAndQueuedTasksAreActive() {
        seed("job-running", "task-1", "RUNNING");
        seed("job-waiting", "task-2", "WAITING_FOR_PROVIDER");
        seed("job-queued", "task-3", "QUEUED");
        assertThat(repository.activeTaskId("ws-1", "job-running")).isEqualTo("task-1");
        assertThat(repository.activeTaskId("ws-1", "job-waiting")).isEqualTo("task-2");
        assertThat(repository.activeTaskId("ws-1", "job-queued")).isEqualTo("task-3");
    }

    @Test
    void terminalOrMissingTaskDoesNotBlockOperations() {
        seed("job-done", "task-done", "COMPLETED");
        seed("job-failed", "task-failed", "FAILED");
        seed("job-cancelled", "task-cancelled", "CANCELLED");
        seed("job-no-task", null, null);
        assertThat(repository.activeTaskId("ws-1", "job-done")).isNull();
        assertThat(repository.activeTaskId("ws-1", "job-failed")).isNull();
        assertThat(repository.activeTaskId("ws-1", "job-cancelled")).isNull();
        assertThat(repository.activeTaskId("ws-1", "job-no-task")).isNull();
    }
}
