package com.noteweave.source;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.storage.ObjectStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 先保存待提交记录，再在事务外发送原件；容量暂满不改变资料的转写阶段。 */
@Service
public class SourceTranscriptionSubmissionService {
    private static final Logger log = LoggerFactory.getLogger(SourceTranscriptionSubmissionService.class);
    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final SourceTranscriptionPort port;
    private final TransactionTemplate transactions;
    private final int batchSize;
    private final long leaseSeconds;

    public SourceTranscriptionSubmissionService(JdbcTemplate jdbc, ObjectStorage storage,
            SourceTranscriptionPort port, PlatformTransactionManager transactionManager,
            @Value("${noteweave.source.transcription-submission-batch-size:4}") int batchSize,
            @Value("${noteweave.source.transcription-submission-lease-seconds:300}") long leaseSeconds,
            @Value("${noteweave.worker.transcription-submit-timeout-seconds:180}") long submitTimeoutSeconds) {
        if (batchSize < 1 || batchSize > 16 || submitTimeoutSeconds < 1 || submitTimeoutSeconds > 3600
                || leaseSeconds < submitTimeoutSeconds + 60 || leaseSeconds > 7200) {
            throw new IllegalArgumentException("转写提交批次或租约配置超出范围");
        }
        this.jdbc = jdbc;
        this.storage = storage;
        this.port = port;
        this.transactions = new TransactionTemplate(transactionManager);
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
    }

    /** 调用者必须在进入 TRANSCRIBING 的同一事务中调用，避免回调先于阶段写入。 */
    void enqueue(SourceTranscriptionPort.Request request, String objectKey, String sha256) {
        if (request.taskId() == null || request.taskId().isBlank()) {
            throw new BusinessException("SOURCE_TRANSCRIPTION_TASK_MISSING", "转写缺少当前解析任务");
        }
        jdbc.update("""
                insert into source_transcription_submission(snapshot_id, task_id, workspace_id, source_id,
                    object_key, sha256, file_name, mime_type, status, next_retry_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """, request.snapshotId(), request.taskId(), request.workspaceId(), request.sourceId(),
                objectKey, sha256, request.fileName(), request.mimeType(), Timestamp.from(Instant.now().minusSeconds(1)));
    }

    @Scheduled(fixedDelayString = "${noteweave.source.transcription-submission-delay-ms:5000}")
    public void dispatchPending() {
        Instant now = Instant.now();
        List<Key> due = jdbc.query("""
                select snapshot_id, task_id from source_transcription_submission
                where (status = 'PENDING' and next_retry_at <= ?)
                   or (status = 'SUBMITTING' and lease_until <= ?)
                order by next_retry_at, created_at limit ?
                """, (rs, n) -> new Key(rs.getString(1), rs.getString(2)),
                Timestamp.from(now), Timestamp.from(now), batchSize);
        for (Key key : due) {
            Submission submission = transactions.execute(ignored -> claim(key));
            if (submission != null) submit(submission);
        }
    }

    private Submission claim(Key key) {
        List<Submission> rows = jdbc.query("""
                select * from source_transcription_submission where snapshot_id = ? and task_id = ? for update
                """, (rs, n) -> new Submission(new SourceTranscriptionPort.Request(rs.getString("workspace_id"),
                rs.getString("source_id"), rs.getString("snapshot_id"), rs.getString("file_name"),
                rs.getString("mime_type"), rs.getString("task_id")), rs.getString("object_key"),
                rs.getString("sha256"), rs.getInt("attempt_count"), rs.getString("status"),
                rs.getTimestamp("next_retry_at"), rs.getTimestamp("lease_until"), null),
                key.snapshotId(), key.taskId());
        if (rows.isEmpty()) return null;
        Submission row = rows.get(0);
        Instant now = Instant.now();
        boolean due = "PENDING".equals(row.status()) && !row.nextRetryAt().toInstant().isAfter(now)
                || "SUBMITTING".equals(row.status()) && row.leaseUntil() != null
                && !row.leaseUntil().toInstant().isAfter(now);
        if (!due) return null;
        if (!current(row)) {
            jdbc.update("""
                    update source_transcription_submission set status = 'CANCELLED', lease_token = null,
                        lease_until = null, updated_at = current_timestamp where snapshot_id = ? and task_id = ?
                    """, key.snapshotId(), key.taskId());
            return null;
        }
        String token = Ids.newId();
        jdbc.update("""
                update source_transcription_submission set status = 'SUBMITTING', lease_token = ?, lease_until = ?,
                    attempt_count = attempt_count + 1, updated_at = current_timestamp
                where snapshot_id = ? and task_id = ?
                """, token, Timestamp.from(now.plusSeconds(leaseSeconds)), key.snapshotId(), key.taskId());
        return new Submission(row.request(), row.objectKey(), row.sha256(), row.attempts() + 1,
                "SUBMITTING", row.nextRetryAt(), row.leaseUntil(), token);
    }

    private boolean current(Submission row) {
        var request = row.request();
        Integer count = jdbc.queryForObject("""
                select count(*) from source_snapshot ss join source s on s.id = ss.source_id
                join task t on t.id = ? and t.workspace_id = s.workspace_id and t.target_id = s.id
                where ss.id = ? and s.id = ? and s.workspace_id = ? and s.status <> 'DELETED'
                  and ss.parse_status = 'PENDING' and ss.processing_stage = 'TRANSCRIBING'
                  and ss.object_key = ? and ss.sha256 = ? and t.task_type = 'SOURCE_PARSE' and t.target_type = 'SOURCE'
                  and t.task_status in ('PENDING', 'RUNNING')
                  and ss.version_no = (select max(x.version_no) from source_snapshot x where x.source_id = s.id)
                  and t.id = (select y.id from task y where y.task_type = 'SOURCE_PARSE' and y.target_id = s.id
                              order by y.created_at desc, y.id desc limit 1)
                """, Integer.class, request.taskId(), request.snapshotId(), request.sourceId(), request.workspaceId(),
                row.objectKey(), row.sha256());
        return count != null && count == 1;
    }

    private void submit(Submission row) {
        Path file = null;
        try {
            file = Files.createTempFile("noteweave-transcription-submit-", ".bin");
            storage.readToFile("noteweave-source", row.objectKey(), file);
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
            }
            if (!row.sha256().equals(HexFormat.of().formatHex(digest.digest()))) {
                throw new BusinessException("SOURCE_TRANSCRIPTION_ORIGINAL_MISMATCH", "转写原件摘要不符");
            }
            // 下载期间资料可能已删除或换任务，重新检查后才发送。
            if (!current(row)) {
                finish(row, "CANCELLED", null, 0);
                return;
            }
            port.submit(row.request(), file);
            finish(row, "SUBMITTED", null, 0);
        } catch (Exception exception) {
            String code = exception instanceof BusinessException business ? business.code()
                    : "SOURCE_TRANSCRIPTION_SUBMIT_FAILED";
            long delay = Math.min(300L, 5L << Math.min(6, Math.max(0, row.attempts() - 1)));
            // 队列和临时故障都使用有限退避；整体等待仍受既有转写超时检查限制。
            delay = Math.min(300L, delay + ThreadLocalRandom.current().nextLong(Math.max(1, delay / 5)));
            finish(row, "PENDING", code, delay);
            log.warn("转写提交暂未完成：snapshotId={}, code={}", row.request().snapshotId(), code);
        } finally {
            if (file != null) {
                try { Files.deleteIfExists(file); } catch (java.io.IOException ignored) { }
            }
        }
    }

    private void finish(Submission row, String status, String code, long delaySeconds) {
        transactions.executeWithoutResult(ignored -> jdbc.update("""
                update source_transcription_submission set status = ?, last_error_code = ?, next_retry_at = ?,
                    lease_token = null, lease_until = null, updated_at = current_timestamp
                where snapshot_id = ? and task_id = ? and status = 'SUBMITTING' and lease_token = ?
                """, status, code, Timestamp.from(Instant.now().plusSeconds(delaySeconds)),
                row.request().snapshotId(), row.request().taskId(), row.token()));
    }

    /** 有新提交记录时必须核对回调任务；旧资料没有记录时沿用旧协议。 */
    boolean acceptsCallback(String snapshotId, String taskId) {
        List<String> tasks = jdbc.queryForList("""
                select st.task_id from source_transcription_submission st
                join task owning on owning.id = st.task_id and owning.workspace_id = st.workspace_id
                    and owning.target_id = st.source_id and owning.target_type = 'SOURCE'
                    and owning.task_type = 'SOURCE_PARSE' and owning.task_status in ('PENDING', 'RUNNING')
                where st.snapshot_id = ? and st.status in ('PENDING', 'SUBMITTING', 'SUBMITTED')
                  and st.task_id = (select t.id from task t where t.task_type = 'SOURCE_PARSE'
                        and t.target_id = st.source_id order by t.created_at desc, t.id desc limit 1)
                """, String.class, snapshotId);
        Integer recorded = jdbc.queryForObject("select count(*) from source_transcription_submission where snapshot_id = ?",
                Integer.class, snapshotId);
        return recorded == null || recorded == 0 || tasks.size() == 1 && tasks.get(0).equals(taskId);
    }

    void completeCallback(String snapshotId, String taskId) {
        if (taskId != null) jdbc.update("""
                update source_transcription_submission set status = 'COMPLETED', lease_token = null,
                    lease_until = null, updated_at = current_timestamp where snapshot_id = ? and task_id = ?
                  and status in ('PENDING', 'SUBMITTING', 'SUBMITTED')
                """, snapshotId, taskId);
    }

    private record Key(String snapshotId, String taskId) { }
    private record Submission(SourceTranscriptionPort.Request request, String objectKey, String sha256,
            int attempts, String status, Timestamp nextRetryAt, Timestamp leaseUntil, String token) { }
}
