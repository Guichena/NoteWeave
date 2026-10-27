package com.noteweave.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * WIKI-04 定向验证：同一资料同一快照的重复入队只产生一个任务；
 * 上一轮任务已经结束（取消/失败/完成）时，新意图必须拿到新任务而不是那张死任务。
 */
class WikiIngestTaskEnqueuerAdapterTest {

    private JdbcTemplate jdbc;
    private WikiIngestTaskEnqueuerAdapter adapter;
    private TestTaskCommandPort tasks;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:wiki-enqueue-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                insert into workspace(id, owner_id, name, status, wiki_enabled)
                values ('ws', 'local-user', '工作台', 'ACTIVE', true)
                """);
        tasks = new TestTaskCommandPort(jdbc);
        adapter = new WikiIngestTaskEnqueuerAdapter(
                tasks, new TaskOutboxEnqueuer(jdbc, new ObjectMapper()), jdbc,
                new DataSourceTransactionManager(dataSource), "noteweave.wiki.ingest");
    }

    @Test
    void repeatedIntentForTheSameSnapshotReusesTheLiveTask() {
        var first = adapter.enqueueIngest(command());
        var second = adapter.enqueueIngest(command());

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.taskId()).isEqualTo(first.taskId());
        assertThat(count("select count(*) from task")).isEqualTo(1);
        assertThat(count("select count(*) from task_outbox")).isEqualTo(1);
    }

    @Test
    void runningTaskIsStillReused() {
        var first = adapter.enqueueIngest(command());
        tasks.startTask(first.taskId());

        var second = adapter.enqueueIngest(command());

        assertThat(second.created()).isFalse();
        assertThat(second.taskId()).isEqualTo(first.taskId());
        assertThat(count("select count(*) from task")).isEqualTo(1);
    }

    @Test
    void cancelledTaskDoesNotSwallowTheNextIntent() {
        var first = adapter.enqueueIngest(command());
        tasks.cancelTask(first.taskId(), "WIKI_SUPERSEDED", "被更新的任务接管", "");

        var second = adapter.enqueueIngest(command());

        assertThat(second.created()).isTrue();
        assertThat(second.taskId()).isNotEqualTo(first.taskId());
        assertThat(count("select count(*) from task")).isEqualTo(2);
        // 语义身份不变，投递代次 +1：旧行保留为事实，新行才是这次要投递的消息
        List<Integer> generations = jdbc.queryForList(
                "select delivery_generation from task_outbox order by delivery_generation", Integer.class);
        assertThat(generations).containsExactly(1, 2);
        assertThat(count("select count(distinct semantic_key) from task_outbox")).isEqualTo(1);
        assertThat(status(first.taskId())).isEqualTo("CANCELLED");
        assertThat(status(second.taskId())).isEqualTo("PENDING");
    }

    @Test
    void failedTaskDoesNotSwallowTheNextIntent() {
        var first = adapter.enqueueIngest(command());
        tasks.failTask(first.taskId(), "WIKI_INDEXED_FAILED", "首次失败", "WIKI_INGEST_FAILED", true);

        var second = adapter.enqueueIngest(command());

        assertThat(second.created()).isTrue();
        assertThat(second.taskId()).isNotEqualTo(first.taskId());
        assertThat(count("select count(*) from task")).isEqualTo(2);
    }

    private com.noteweave.knowledge.WikiIngestTaskEnqueuerPort.IngestCommand command() {
        return new com.noteweave.knowledge.WikiIngestTaskEnqueuerPort.IngestCommand(
                "ws", "source-1", "snapshot-1", "v1", "ingest", "资料 READY 意图确认");
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private String status(String taskId) {
        return jdbc.queryForObject("select task_status from task where id = ?", String.class, taskId);
    }

    /** 只落 task 表的最小实现：入队适配器需要按任务状态判断能否复用。 */
    private static final class TestTaskCommandPort implements TaskCommandPort {

        private final JdbcTemplate jdbc;
        private int sequence = 0;

        private TestTaskCommandPort(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public String createTask(
                String workspaceId, String taskType, String targetType, String targetId,
                String phase, String message) {
            String taskId = "task-" + (++sequence);
            jdbc.update("""
                    insert into task(id, workspace_id, task_type, task_status, target_type, target_id,
                        progress_phase, progress_message)
                    values (?, ?, ?, 'PENDING', ?, ?, ?, ?)
                    """, taskId, workspaceId, taskType, targetType, targetId, phase, message);
            return taskId;
        }

        @Override
        public void startTask(String taskId) {
            move(taskId, "RUNNING");
        }

        @Override
        public void cancelTask(String taskId, String phase, String message, String resultRef) {
            move(taskId, "CANCELLED");
        }

        @Override
        public void completeTask(String taskId, String phase, String message, String resultRef) {
            move(taskId, "COMPLETED");
        }

        @Override
        public void failTask(
                String taskId, String phase, String message, String errorCode, boolean retryable) {
            move(taskId, "FAILED");
        }

        private void move(String taskId, String status) {
            jdbc.update("update task set task_status = ?, updated_at = current_timestamp where id = ?",
                    status, taskId);
        }
    }
}
