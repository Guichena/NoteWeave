package com.noteweave.source;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * TR-01：转写看门狗按「进入转写阶段的时刻」计时，不再按 {@code source.updated_at}。
 *
 * <p>旧口径下改标题这类无关更新会重置计时，而 4 小时录音即使 Worker 仍在正常转写，
 * 也会在固定时限后被误杀；这里把两个方向都钉住：无关更新不能杀、真正的超时仍要收尾。</p>
 */
class SourceTranscriptionWatchdogTest {

    private static final String WORKSPACE = "workspace-1";
    private static final String SOURCE = "source-1";
    private static final String SNAPSHOT = "snapshot-1";
    private static final String TASK = "task-1";
    private static final Timestamp LONG_AGO = Timestamp.from(Instant.parse("2020-01-01T00:00:00Z"));

    private JdbcTemplate jdbc;
    private SourceParseService parseService;
    private SourceParseFailureFinalizer failureFinalizer;
    private SourceTranscriptionService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:transcription-watchdog-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                create table source(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    status varchar(32) not null,
                    updated_at timestamp not null
                )
                """);
        jdbc.execute("""
                create table source_snapshot(
                    id varchar(36) primary key,
                    source_id varchar(36) not null,
                    processing_stage varchar(32),
                    parse_status varchar(32),
                    version_no int not null
                )
                """);
        jdbc.execute("""
                create table source_transcription_submission(
                    snapshot_id varchar(36) not null,
                    task_id varchar(36) not null,
                    status varchar(32) not null,
                    stage_started_at timestamp null,
                    heartbeat_at timestamp null,
                    primary key (snapshot_id, task_id)
                )
                """);
        jdbc.update("insert into source(id, workspace_id, status, updated_at) values (?, ?, 'PROCESSING', ?)",
                SOURCE, WORKSPACE, Timestamp.from(Instant.now()));
        jdbc.update("""
                insert into source_snapshot(id, source_id, processing_stage, parse_status, version_no)
                values (?, ?, 'TRANSCRIBING', 'PENDING', 1)
                """, SNAPSHOT, SOURCE);

        parseService = mock(SourceParseService.class);
        failureFinalizer = mock(SourceParseFailureFinalizer.class);
        when(parseService.latestParseTaskId(SOURCE)).thenReturn(TASK);
        service = new SourceTranscriptionService(jdbc, parseService, failureFinalizer, 180);
    }

    @Test
    void unrelatedSourceUpdateDoesNotKillAnActiveTranscription() {
        submission(Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        // 资料本身被无关操作碰过（例如改标题），但转写阶段才刚开始
        jdbc.update("update source set updated_at = ? where id = ?", LONG_AGO, SOURCE);

        service.failStaleTranscriptions();

        verify(failureFinalizer, never()).finalizeProcessingFailed(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void staleStageAnchorStillEndsAsATimeout() {
        submission(LONG_AGO, LONG_AGO);

        service.failStaleTranscriptions();

        verify(failureFinalizer).finalizeProcessingFailed(
                eq(TASK), eq(WORKSPACE), eq(SOURCE), eq(SNAPSHOT),
                eq("SOURCE_TRANSCRIPTION_TIMEOUT"),
                eq("音视频转写超过 180 分钟没有返回结果"), eq(true));
    }

    @Test
    void recentHeartbeatKeepsALongRunningTranscriptionAlive() {
        // 阶段开始很久了，但心跳刚刚更新过：Worker 仍在推进，不能杀
        submission(LONG_AGO, Timestamp.from(Instant.now()));

        service.failStaleTranscriptions();

        verify(failureFinalizer, never()).finalizeProcessingFailed(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void legacySnapshotWithoutSubmissionFallsBackToSourceUpdatedAt() {
        jdbc.update("update source set updated_at = ? where id = ?", LONG_AGO, SOURCE);

        service.failStaleTranscriptions();

        verify(failureFinalizer).finalizeProcessingFailed(
                eq(TASK), eq(WORKSPACE), eq(SOURCE), eq(SNAPSHOT), eq("SOURCE_TRANSCRIPTION_TIMEOUT"),
                any(String.class), eq(true));
    }

    @Test
    void freshLegacySnapshotWithoutSubmissionIsLeftAlone() {
        jdbc.update("update source set updated_at = ? where id = ?", Timestamp.from(Instant.now()), SOURCE);

        service.failStaleTranscriptions();

        verify(failureFinalizer, never()).finalizeProcessingFailed(
                any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    private void submission(Timestamp stageStartedAt, Timestamp heartbeatAt) {
        jdbc.update("""
                insert into source_transcription_submission(
                    snapshot_id, task_id, status, stage_started_at, heartbeat_at)
                values (?, ?, 'SUBMITTED', ?, ?)
                """, SNAPSHOT, TASK, stageStartedAt, heartbeatAt);
    }
}
