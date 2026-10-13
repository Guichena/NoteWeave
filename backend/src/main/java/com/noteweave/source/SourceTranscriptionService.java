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
    private SourceTranscriptionSubmissionService submissions;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSubmissions(SourceTranscriptionSubmissionService submissions) {
        this.submissions = submissions;
    }

    public SourceTranscriptionService(JdbcTemplate jdbcTemplate, SourceParseService parseService,
                                      SourceParseFailureFinalizer failureFinalizer,
                                      @Value("${noteweave.source.transcription-timeout-minutes:180}") long timeoutMinutes) {
        this.jdbcTemplate = jdbcTemplate;
        this.parseService = parseService;
        this.failureFinalizer = failureFinalizer;
        this.timeoutMinutes = timeoutMinutes;
    }

    /** 返回是否接受了这次回调；快照不在转写阶段（重复回调、资料已删除）时返回 false。 */
    @org.springframework.transaction.annotation.Transactional
    public boolean handle(String snapshotId, TranscriptionCallback callback) {
        Snapshot snapshot = snapshot(snapshotId, callback.workspaceId(), callback.sourceId());
        if (snapshot == null || !SourcePipelineStages.STAGE_TRANSCRIBING.equals(snapshot.stage())) {
            log.info("Ignore transcription callback outside the transcribing stage: snapshotId={}", snapshotId);
            return false;
        }
        if (submissions != null && !submissions.acceptsCallback(snapshotId, callback.taskId())) return false;
        if ("COMPLETED".equals(callback.status())) {
            String transcript = callback.transcriptText() == null ? "" : callback.transcriptText().trim();
            if (transcript.isBlank()) {
                fail(callback.workspaceId(), callback.sourceId(), snapshotId, "SOURCE_TRANSCRIPT_EMPTY",
                        "音视频中没有识别出可用的语音内容", false);
                completeSubmission(snapshotId, callback.taskId());
                return true;
            }
            boolean accepted = parseService.acceptTranscript(callback.workspaceId(), callback.sourceId(), snapshotId,
                    transcript, callback.durationSeconds(), callback.correctedLineCount());
            if (accepted) completeSubmission(snapshotId, callback.taskId());
            return accepted;
        }
        fail(callback.workspaceId(), callback.sourceId(), snapshotId,
                blankToDefault(callback.errorCode(), "SOURCE_TRANSCRIPTION_FAILED"),
                blankToDefault(callback.errorMessage(), "音视频转写失败"), true);
        completeSubmission(snapshotId, callback.taskId());
        return true;
    }

    private void completeSubmission(String snapshotId, String taskId) {
        if (submissions != null) submissions.completeCallback(snapshotId, taskId);
    }

    /** Worker 重启或回调丢失时，转写会一直停在转写中；超过时限后按失败收尾。 */
    @Scheduled(fixedDelayString = "${noteweave.source.transcription-watchdog-ms:300000}")
    @org.springframework.transaction.annotation.Transactional
    public void failStaleTranscriptions() {
        java.sql.Timestamp cutoff = java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(timeoutMinutes * 60));
        List<Snapshot> stale = jdbcTemplate.query("""
                select ss.id, ss.source_id, s.workspace_id, ss.processing_stage
                from source_snapshot ss
                join source s on s.id = ss.source_id
                where ss.processing_stage = 'TRANSCRIBING' and ss.parse_status = 'PENDING'
                  and s.status <> 'DELETED' and s.updated_at < ?
                  and ss.version_no = (select max(x.version_no) from source_snapshot x where x.source_id = s.id)
                """, (rs, rowNum) -> new Snapshot(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                cutoff);
        for (Snapshot snapshot : stale) {
            Snapshot locked = snapshot(snapshot.id(), snapshot.workspaceId(), snapshot.sourceId());
            if (locked == null || !SourcePipelineStages.STAGE_TRANSCRIBING.equals(locked.stage())) continue;
            java.sql.Timestamp entered = jdbcTemplate.queryForObject(
                    "select updated_at from source where id = ?", java.sql.Timestamp.class, snapshot.sourceId());
            // 重新处理或回调可能已改变状态，行锁内重新核对，避免使新任务失败。
            if (entered == null || !entered.before(cutoff)) continue;
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
                  and ss.parse_status = 'PENDING'
                  and ss.version_no = (select max(x.version_no) from source_snapshot x where x.source_id = s.id)
                for update
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
            String errorMessage,
            String taskId
    ) {
        public TranscriptionCallback {
            if (!"COMPLETED".equals(status) && !"FAILED".equals(status)) {
                throw new IllegalArgumentException("转写回调状态必须是 COMPLETED 或 FAILED");
            }
            if (durationSeconds != null && (!Double.isFinite(durationSeconds) || durationSeconds < 0)
                    || segmentCount != null && segmentCount < 0
                    || correctedLineCount != null && correctedLineCount < 0
                    || transcriptText != null && transcriptText.length() > 20_000_000) {
                throw new IllegalArgumentException("转写回调的计数、时长或正文长度无效");
            }
        }
        public TranscriptionCallback(String workspaceId, String sourceId, String status, String transcriptText,
                String language, Double durationSeconds, Integer segmentCount, Integer correctedLineCount,
                String errorCode, String errorMessage) {
            this(workspaceId, sourceId, status, transcriptText, language, durationSeconds, segmentCount,
                    correctedLineCount, errorCode, errorMessage, null);
        }
    }

    private record Snapshot(String id, String sourceId, String workspaceId, String stage) {
    }
}
