package com.noteweave.source;

import com.noteweave.task.TaskService;
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

    /** 看门狗超时收尾用的错误码；迟到回调只复活这一类失败。 */
    static final String TIMEOUT_CODE = "SOURCE_TRANSCRIPTION_TIMEOUT";

    private final JdbcTemplate jdbcTemplate;
    private final SourceParseService parseService;
    private final SourceParseFailureFinalizer failureFinalizer;
    private final long timeoutMinutes;
    private SourceTranscriptionSubmissionService submissions;
    private TaskService taskService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSubmissions(SourceTranscriptionSubmissionService submissions) {
        this.submissions = submissions;
    }

    /** 迟到回调复活要把任务从 FAILED 推回运行中；生产装配必然存在。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTaskService(TaskService taskService) {
        this.taskService = taskService;
    }

    public SourceTranscriptionService(JdbcTemplate jdbcTemplate, SourceParseService parseService,
                                      SourceParseFailureFinalizer failureFinalizer,
                                      @Value("${noteweave.source.transcription-timeout-minutes:480}") long timeoutMinutes) {
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
            // TR-01：看门狗可能已经按超时收尾，而 Worker 后来才把稿子算出来——先尝试复活
            return reviveLateCompleted(snapshotId, callback);
        }
        if (submissions != null && !submissions.acceptsCallback(snapshotId, callback.taskId())) return false;
        if ("COMPLETED".equals(callback.status())) {
            if (callback.mediaDocument() != null) {
                boolean accepted = parseService.acceptMediaDocument(callback.workspaceId(), callback.sourceId(), snapshotId, callback.mediaDocument());
                if (accepted) completeSubmission(snapshotId, callback.taskId());
                return accepted;
            }
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

    /**
     * TR-01：迟到的转写回调。
     *
     * <p>看门狗按超时收尾之后，Worker 仍可能把稿子算出来再回调。旧实现一律丢弃，
     * Worker 那边也按「已丢弃」删掉输出，等于白算一遍。这里对「被看门狗按超时收尾」的快照做复活：
     * 恢复快照与资料的处理状态、把任务推回运行中，再把文字稿交给切片阶段。</p>
     *
     * <p>只复活超时这一种失败。用户取消、删除、其它解析失败都不该被一次迟到回调改写，
     * 那些情况仍然丢弃，保持原语义。</p>
     */
    private boolean reviveLateCompleted(String snapshotId, TranscriptionCallback callback) {
        if (!"COMPLETED".equals(callback.status())) {
            log.info("Ignore transcription callback outside the transcribing stage: snapshotId={}",
                    snapshotId);
            return false;
        }
        String transcript = callback.transcriptText() == null ? "" : callback.transcriptText().trim();
        if (transcript.isBlank()) {
            log.info("Ignore late transcription callback without usable text: snapshotId={}", snapshotId);
            return false;
        }
        LateSnapshot late = lateSnapshot(snapshotId, callback.workspaceId(), callback.sourceId());
        if (late == null) {
            log.info("Ignore transcription callback outside the transcribing stage: snapshotId={}",
                    snapshotId);
            return false;
        }
        if (!TIMEOUT_CODE.equals(late.errorCode())) {
            log.info("Ignore late transcription callback for a non-timeout failure: snapshotId={}, code={}",
                    snapshotId, late.errorCode());
            return false;
        }
        if (taskService == null) {
            log.warn("Late transcription callback cannot revive without task service: snapshotId={}",
                    snapshotId);
            return false;
        }
        int restored = jdbcTemplate.update("""
                update source_snapshot set parse_status = 'PENDING', processing_stage = ?, index_status = 'PENDING'
                where id = ? and source_id = ? and parse_status = 'FAILED'
                """, SourcePipelineStages.STAGE_TRANSCRIBING, snapshotId, callback.sourceId());
        if (restored != 1) {
            log.info("Ignore late transcription callback on a snapshot that is no longer failed: snapshotId={}",
                    snapshotId);
            return false;
        }
        jdbcTemplate.update("""
                update source set status = 'PROCESSING', parse_status = 'PENDING', index_status = 'PENDING',
                    updated_at = current_timestamp
                where id = ? and workspace_id = ? and status <> 'DELETED'
                """, callback.sourceId(), callback.workspaceId());
        taskService.redriveTask(late.taskId(), true);
        boolean accepted = parseService.acceptTranscript(callback.workspaceId(), callback.sourceId(),
                snapshotId, transcript, callback.durationSeconds(), callback.correctedLineCount());
        if (accepted) {
            completeSubmission(snapshotId, callback.taskId());
            log.info("Revived late transcription callback: snapshotId={}, taskId={}, characters={}",
                    snapshotId, late.taskId(), transcript.length());
        } else {
            log.warn("Late transcription callback revived the task but the transcript was not queued: snapshotId={}",
                    snapshotId);
        }
        return accepted;
    }

    /** 迟到回调可复活的快照：解析已失败、仍是当前版本、资料未删除；带最近解析任务的错误码。 */
    private LateSnapshot lateSnapshot(String snapshotId, String workspaceId, String sourceId) {
        List<String> failed = jdbcTemplate.query("""
                select ss.id
                from source_snapshot ss
                join source s on s.id = ss.source_id
                where ss.id = ? and ss.source_id = ? and s.workspace_id = ? and s.status <> 'DELETED'
                  and ss.parse_status = 'FAILED'
                  and ss.version_no = (select max(x.version_no) from source_snapshot x where x.source_id = ss.source_id)
                for update
                """, (rs, rowNum) -> rs.getString(1), snapshotId, sourceId, workspaceId);
        if (failed.isEmpty()) return null;
        String taskId = parseService.latestParseTaskId(sourceId);
        if (taskId == null) return null;
        List<String> codes = jdbcTemplate.queryForList(
                "select error_code from task where id = ?", String.class, taskId);
        return new LateSnapshot(taskId, codes.isEmpty() ? null : codes.get(0));
    }

    /**
     * Worker 重启或回调丢失时，转写会一直停在转写中；超过时限后按失败收尾。
     *
     * <p>TR-01：计时锚点改为「进入转写阶段的时刻」（提交记录的 stage_started_at；接入 Worker
     * 心跳后取 heartbeat_at），不再用 {@code source.updated_at}。旧口径下改标题这类无关更新会
     * 重置计时，而 4 小时录音即使 Worker 仍在正常转写也会被固定时限误杀。
     * 没有提交记录的旧资料仍退回旧口径，保证兼容。</p>
     */
    @Scheduled(fixedDelayString = "${noteweave.source.transcription-watchdog-ms:300000}")
    @org.springframework.transaction.annotation.Transactional
    public void failStaleTranscriptions() {
        java.sql.Timestamp cutoff = java.sql.Timestamp.from(
                java.time.Instant.now().minusSeconds(timeoutMinutes * 60));
        List<StaleCandidate> stale = jdbcTemplate.query("""
                select ss.id, ss.source_id, s.workspace_id, ss.processing_stage,
                       coalesce(
                           (select max(coalesce(sub.heartbeat_at, sub.stage_started_at))
                            from source_transcription_submission sub where sub.snapshot_id = ss.id),
                           s.updated_at) as stage_since
                from source_snapshot ss
                join source s on s.id = ss.source_id
                where ss.processing_stage = 'TRANSCRIBING' and ss.parse_status = 'PENDING'
                  and s.status <> 'DELETED'
                  and ss.version_no = (select max(x.version_no) from source_snapshot x where x.source_id = s.id)
                """, (rs, rowNum) -> new StaleCandidate(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getTimestamp(5)));
        for (StaleCandidate candidate : stale) {
            if (candidate.stageSince() == null || !candidate.stageSince().before(cutoff)) continue;
            Snapshot locked = snapshot(candidate.id(), candidate.workspaceId(), candidate.sourceId());
            if (locked == null || !SourcePipelineStages.STAGE_TRANSCRIBING.equals(locked.stage())) continue;
            // 行锁内重新核对锚点：并发心跳或重新提交可能刚把计时推后，避免使新任务失败。
            java.sql.Timestamp anchor = stageAnchor(candidate.id(), candidate.sourceId());
            if (anchor == null || !anchor.before(cutoff)) continue;
            fail(candidate.workspaceId(), candidate.sourceId(), candidate.id(), TIMEOUT_CODE,
                    "音视频转写超过 " + timeoutMinutes + " 分钟没有返回结果", true);
        }
    }

    /** 转写阶段的计时锚点：优先提交记录的心跳/开始时间，旧资料退回 source.updated_at。 */
    private java.sql.Timestamp stageAnchor(String snapshotId, String sourceId) {
        return jdbcTemplate.queryForObject("""
                select coalesce(
                    (select max(coalesce(sub.heartbeat_at, sub.stage_started_at))
                     from source_transcription_submission sub where sub.snapshot_id = ?),
                    (select updated_at from source where id = ?))
                """, java.sql.Timestamp.class, snapshotId, sourceId);
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
            String taskId,
            @com.fasterxml.jackson.annotation.JsonProperty("media_document") SourceMediaDocument mediaDocument
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
                    correctedLineCount, errorCode, errorMessage, null, null);
        }

        public TranscriptionCallback(String workspaceId, String sourceId, String status, String transcriptText,
                String language, Double durationSeconds, Integer segmentCount, Integer correctedLineCount,
                String errorCode, String errorMessage, String taskId) {
            this(workspaceId, sourceId, status, transcriptText, language, durationSeconds, segmentCount,
                    correctedLineCount, errorCode, errorMessage, taskId, null);
        }
    }

    private record Snapshot(String id, String sourceId, String workspaceId, String stage) {
    }

    /** 看门狗候选：stageSince 是进入转写阶段的锚点，为空表示无法判定。 */
    private record StaleCandidate(String id, String sourceId, String workspaceId, String stage,
                                  java.sql.Timestamp stageSince) {
    }

    /** 迟到回调的复活依据：最近解析任务与它被收尾时的错误码。 */
    private record LateSnapshot(String taskId, String errorCode) {
    }
}
