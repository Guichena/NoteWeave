package com.noteweave.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.noteweave.task.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * TR-01：迟到的转写回调不能白算。
 *
 * <p>看门狗按超时把任务收尾之后，Worker 可能过一会儿才把稿子算完并回调。
 * 旧实现一律丢弃，Worker 那边也按「已丢弃」删掉输出，等于整段音频白转一遍。
 * 这里钉住「被看门狗超时收尾的可以复活」与「其它失败不复活」两个方向。</p>
 */
class SourceTranscriptionLateCallbackTest {

    private static final String WORKSPACE = "workspace-1";
    private static final String SOURCE = "source-1";
    private static final String SNAPSHOT = "snapshot-1";
    private static final String TASK = "task-1";

    private JdbcTemplate jdbc;
    private SourceParseService parseService;
    private TaskService taskService;
    private SourceTranscriptionService service;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:transcription-late-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("""
                create table source(id varchar(36) primary key, workspace_id varchar(36) not null,
                    status varchar(32) not null, parse_status varchar(32), index_status varchar(32),
                    updated_at timestamp default current_timestamp)
                """);
        jdbc.execute("""
                create table source_snapshot(id varchar(36) primary key, source_id varchar(36) not null,
                    processing_stage varchar(32), parse_status varchar(32), index_status varchar(32),
                    version_no int not null)
                """);
        jdbc.execute("create table task(id varchar(36) primary key, generation int not null default 1, error_code varchar(80))");
        // 看门狗收尾后的状态：快照与资料都是 FAILED，任务带超时错误码
        jdbc.update("""
                insert into source(id, workspace_id, status, parse_status, index_status)
                values (?, ?, 'FAILED', 'FAILED', 'FAILED')
                """, SOURCE, WORKSPACE);
        jdbc.update("""
                insert into source_snapshot(id, source_id, processing_stage, parse_status, index_status, version_no)
                values (?, ?, 'TRANSCRIBING', 'FAILED', 'FAILED', 1)
                """, SNAPSHOT, SOURCE);
        jdbc.update("insert into task(id, error_code) values (?, ?)", TASK, SourceTranscriptionService.TIMEOUT_CODE);

        parseService = mock(SourceParseService.class);
        taskService = mock(TaskService.class);
        when(parseService.latestParseTaskId(SOURCE)).thenReturn(TASK);
        service = new SourceTranscriptionService(jdbc, parseService,
                mock(SourceParseFailureFinalizer.class), 480);
        service.setTaskService(taskService);
    }

    @Test
    void lateCompletedCallbackRevivesTheTimedOutSnapshotInsteadOfDroppingTheTranscript() {
        when(parseService.acceptTranscript(eq(WORKSPACE), eq(SOURCE), eq(SNAPSHOT), anyString(), any(), anyInt()))
                .thenReturn(true);

        boolean accepted = service.handle(SNAPSHOT, completed("这是迟到的文字稿"));

        assertThat(accepted).isTrue();
        assertThat(jdbc.queryForObject("select parse_status from source_snapshot where id = ?", String.class, SNAPSHOT))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select processing_stage from source_snapshot where id = ?", String.class, SNAPSHOT))
                .isEqualTo(SourcePipelineStages.STAGE_TRANSCRIBING);
        assertThat(jdbc.queryForObject("select status from source where id = ?", String.class, SOURCE))
                .isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("select index_status from source where id = ?", String.class, SOURCE))
                .isEqualTo("PENDING");
        verify(taskService).redriveTask(TASK, true);
        verify(parseService).acceptTranscript(eq(WORKSPACE), eq(SOURCE), eq(SNAPSHOT),
                eq("这是迟到的文字稿"), eq(120.0), eq(3));
    }

    @Test
    void lateCallbackForANonTimeoutFailureIsStillIgnored() {
        jdbc.update("update task set error_code = 'SOURCE_PARSE_FAILED' where id = ?", TASK);

        boolean accepted = service.handle(SNAPSHOT, completed("不该被采用的稿子"));

        assertThat(accepted).isFalse();
        assertThat(jdbc.queryForObject("select parse_status from source_snapshot where id = ?", String.class, SNAPSHOT))
                .isEqualTo("FAILED");
        verifyNoInteractions(taskService);
        verify(parseService, never()).acceptTranscript(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void lateFailureCallbackAndRepeatedCallbackAreIgnored() {
        SourceTranscriptionService.TranscriptionCallback failed = new SourceTranscriptionService.TranscriptionCallback(
                WORKSPACE, SOURCE, "FAILED", null, "zh", 120.0, 3, 3, "WORKER_CRASH", "worker 退出", TASK);
        assertThat(service.handle(SNAPSHOT, failed)).isFalse();

        // 已经进入切片阶段（重复回调）不再复活
        jdbc.update("""
                update source_snapshot set parse_status = 'PENDING', processing_stage = 'CHUNKING' where id = ?
                """, SNAPSHOT);
        assertThat(service.handle(SNAPSHOT, completed("重复投递"))).isFalse();
        verifyNoInteractions(taskService);
    }

    private SourceTranscriptionService.TranscriptionCallback completed(String transcript) {
        return new SourceTranscriptionService.TranscriptionCallback(
                WORKSPACE, SOURCE, "COMPLETED", transcript, "zh", 120.0, 3, 3, null, null, TASK);
    }
}
