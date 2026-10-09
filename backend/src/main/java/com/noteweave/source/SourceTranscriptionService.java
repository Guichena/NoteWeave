package com.noteweave.source;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 处理 Worker 回调的转写结果，并清理长时间没有回调的转写。
 * 转写成功时文字稿进入切片阶段；失败或超时按解析失败收尾，用户可以重新处理。
 */
@Service
public class SourceTranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SourceTranscriptionService.class);

    private final JdbcTemplate jdbcTemplate;
    private final SourceParseService parseService;
    private final SourceParseFailureFinalizer failureFinalizer;
    private final long timeoutMinutes;

    public SourceTranscriptionService(JdbcTemplate jdbcTemplate, SourceParseService parseService,
                                      SourceParseFailureFinalizer failureFinalizer,
                                      @Value("${noteweave.source.transcription-timeout-minutes:180}") long timeoutMinutes) {
        this.jdbcTemplate = jdbcTemplate;
        this.parseService = parseService;
        this.failureFinalizer = failureFinalizer;
        this.timeoutMinutes = timeoutMinutes;
    }

    /** 返回是否接受了这次回调；快照不在转写阶段（重复回调、资料已删除）时返回 false。 */
    public boolean handle(String snapshotId, TranscriptionCallback callback) {
        Snapshot snapshot = snapshot(snapshotId, callback.workspaceId(), callback.sourceId());
        if (snapshot == null || !SourcePipelineStages.STAGE_TRANSCRIBING.equals(snapshot.stage())) {
            log.info("Ignore transcription callback outside the transcribing stage: snapshotId={}", snapshotId);
            return false;
        }
        if ("COMPLETED".equals(callback.status())) {
            String transcript = callback.transcriptText() == null ? "" : callback.transcriptText().trim();
            if (transcript.isBlank()) {
                fail(callback.workspaceId(), callback.sourceId(), snapshotId, "SOURCE_TRANSCRIPT_EMPTY",
                        "音视频中没有识别出可用的语音内容", false);
                return true;
            }
            return parseService.acceptTranscript(callback.workspaceId(), callback.sourceId(), snapshotId,
                    transcript, callback.durationSeconds(), callback.correctedLineCount());
        }
        fail(callback.workspaceId(), callback.sourceId(), snapshotId,
                blankToDefault(callback.errorCode(), "SOURCE_TRANSCRIPTION_FAILED"),
                blankToDefault(callback.errorMessage(), "音视频转写失败"), true);
        return true;
    }

    /** Worker 重启或回调丢失时，转写会一直停在转写中；超过时限后按失败收尾。 */
    @Scheduled(fixedDelayString = "${noteweave.source.transcription-watchdog-ms:300000}")
    public void failStaleTranscriptions() {
        List<Snapshot> stale = jdbcTemplate.query("""
                select ss.id, ss.source_id, s.workspace_id, ss.processing_stage
                from source_snapshot ss
                join source s on s.id = ss.source_id
                where ss.processing_stage = 'TRANSCRIBING' and ss.parse_status = 'PENDING'
                  and s.status <> 'DELETED' and s.updated_at < ?
                """, (rs, rowNum) -> new Snapshot(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(timeoutMinutes * 60)));
        for (Snapshot snapshot : stale) {
            fail(snapshot.workspaceId(), snapshot.sourceId(), snapshot.id(), "SOURCE_TRANSCRIPTION_TIMEOUT",
                    "音视频转写超过 " + timeoutMinutes + " 分钟没有返回结果", true);
        }
    }

    private void fail(String workspaceId, String sourceId, String snapshotId, String code, String message,
                      boolean retryable) {
        String taskId = parseService.latestParseTaskId(sourceId);
        if (taskId == null) {
            log.warn("Transcription failed but no parse task exists: sourceId={}", sourceId);
            return;
        }
        failureFinalizer.finalizeProcessingFailed(taskId, workspaceId, sourceId, snapshotId, code, message, retryable);
    }

    private Snapshot snapshot(String snapshotId, String workspaceId, String sourceId) {
        List<Snapshot> rows = jdbcTemplate.query("""
                select ss.id, ss.source_id, s.workspace_id, ss.processing_stage
                from source_snapshot ss
                join source s on s.id = ss.source_id
                where ss.id = ? and ss.source_id = ? and s.workspace_id = ? and s.status <> 'DELETED'
                """, (rs, rowNum) -> new Snapshot(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                snapshotId, sourceId, workspaceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public record TranscriptionCallback(
            String workspaceId,
            String sourceId,
            String status,
            String transcriptText,
            String language,
            Double durationSeconds,
            Integer segmentCount,
            // Worker 用大模型校对识别错误后实际改动的行数
            Integer correctedLineCount,
            String errorCode,
            String errorMessage
    ) {
    }

    private record Snapshot(String id, String sourceId, String workspaceId, String stage) {
    }
}
