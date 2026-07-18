package com.noteweave.worker;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.noteweave.common.BusinessException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import com.noteweave.common.Ids;
import com.noteweave.task.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ArtifactOutboxDispatcherService {

    private static final String ARTIFACT_TOPIC = "noteweave.artifact.job";
    private static final Duration STALE_CLAIM_AFTER = Duration.ofMinutes(5);
    private static final int MAX_ATTEMPTS = 5;
    private static final Logger log = LoggerFactory.getLogger(ArtifactOutboxDispatcherService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ArtifactOutboxPublisher artifactOutboxPublisher;
    private final TaskService taskService;
    private final String dispatcherId = "artifact-dispatcher-" + UUID.randomUUID();
    private final Counter dispatchedCounter;
    private final Counter failedCounter;
    private final Counter deadLetterCounter;
    private final Counter redriveCounter;
    private final TransactionTemplate transactionTemplate;

    public ArtifactOutboxDispatcherService(
            JdbcTemplate jdbcTemplate,
            ArtifactOutboxPublisher artifactOutboxPublisher,
            TaskService taskService,
            MeterRegistry meterRegistry,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.artifactOutboxPublisher = artifactOutboxPublisher;
        this.taskService = taskService;
        this.dispatchedCounter = meterRegistry.counter("noteweave.artifact.outbox.dispatched");
        this.failedCounter = meterRegistry.counter("noteweave.artifact.outbox.failed");
        this.deadLetterCounter = meterRegistry.counter("noteweave.artifact.outbox.dead_lettered");
        this.redriveCounter = meterRegistry.counter("noteweave.artifact.outbox.redriven");
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public ArtifactOutboxDispatchResponse dispatchReadyArtifactJobs(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 20));
        List<OutboxRow> rows = jdbcTemplate.query("""
                select id, task_id, message_key, payload_json, attempt_count
                from task_outbox
                where topic = ?
                  and (
                    (status = 'READY' and (next_attempt_at is null or next_attempt_at <= current_timestamp))
                    or (status = 'PROCESSING' and (lease_until is null or lease_until < current_timestamp))
                  )
                  and attempt_count < ?
                order by created_at asc, id asc
                limit ?
                """, (rs, rowNum) -> new OutboxRow(
                rs.getString("id"),
                rs.getString("task_id"),
                rs.getString("message_key"),
                rs.getString("payload_json"),
                rs.getInt("attempt_count")
        ), ARTIFACT_TOPIC, MAX_ATTEMPTS, safeLimit);

        int dispatched = 0;
        for (OutboxRow row : rows) {
            int claimed = jdbcTemplate.update("""
                    update task_outbox
                    set status = 'PROCESSING', claimed_at = current_timestamp,
                        lease_owner = ?, lease_until = ?,
                        attempt_count = attempt_count + 1, last_error = null
                    where id = ?
                      and (
                        (status = 'READY' and (next_attempt_at is null or next_attempt_at <= current_timestamp))
                        or (status = 'PROCESSING' and (lease_until is null or lease_until < current_timestamp))
                      )
                      and attempt_count < ?
                    """,
                    dispatcherId,
                    Timestamp.from(Instant.now().plus(STALE_CLAIM_AFTER)),
                    row.outboxId(),
                    MAX_ATTEMPTS);
            if (claimed == 0) {
                continue;
            }
            try {
                artifactOutboxPublisher.publish(ARTIFACT_TOPIC, row.messageKey(), row.payloadJson());
                int finalized = jdbcTemplate.update("""
                        update task_outbox
                        set status = 'SENT', sent_at = current_timestamp, claimed_at = null,
                            lease_owner = null, lease_until = null,
                            next_attempt_at = null, last_error = null
                        where id = ? and status = 'PROCESSING' and lease_owner = ?
                        """, row.outboxId(), dispatcherId);
                if (finalized == 1) {
                    dispatched++;
                    dispatchedCounter.increment();
                } else {
                    log.warn("Artifact outbox lease was lost before SENT transition; outboxId={}", row.outboxId());
                }
            } catch (RuntimeException ex) {
                int attemptNo = row.attemptCount() + 1;
                boolean exhausted = attemptNo >= MAX_ATTEMPTS;
                long backoffSeconds = Math.min(60L, 1L << Math.min(attemptNo, 6));
                if (exhausted) {
                    Boolean transitioned = transactionTemplate.execute(status -> {
                        int deadLettered = jdbcTemplate.update("""
                                update task_outbox
                                set status = 'DEAD_LETTER', claimed_at = null,
                                    lease_owner = null, lease_until = null, next_attempt_at = null,
                                    dead_lettered_at = current_timestamp, last_error = ?
                                where id = ? and status = 'PROCESSING' and lease_owner = ?
                                """, abbreviate(ex.getMessage(), 1000), row.outboxId(), dispatcherId);
                        if (deadLettered == 0) {
                            return false;
                        }
                        taskService.failTask(
                                row.taskId(),
                                "OUTBOX_DEAD_LETTER",
                                "Artifact Worker 投递连续失败，已进入死信队列",
                                "ARTIFACT_DISPATCH_EXHAUSTED",
                                true
                        );
                        jdbcTemplate.update("""
                                update artifact_job set status = 'FAILED', updated_at = current_timestamp
                                where task_id = ?
                                """, row.taskId());
                        return true;
                    });
                    if (Boolean.TRUE.equals(transitioned)) {
                        deadLetterCounter.increment();
                    }
                } else {
                    jdbcTemplate.update("""
                            update task_outbox
                            set status = 'READY', claimed_at = null,
                                lease_owner = null, lease_until = null,
                                next_attempt_at = ?, last_error = ?
                            where id = ? and status = 'PROCESSING' and lease_owner = ?
                            """,
                            Timestamp.from(Instant.now().plusSeconds(backoffSeconds)),
                            abbreviate(ex.getMessage(), 1000),
                            row.outboxId(),
                            dispatcherId);
                }
                failedCounter.increment();
                log.warn(
                        "Artifact outbox dispatch failed; taskId={}, attempt={}, retryInSeconds={}, error={}",
                        row.taskId(),
                        attemptNo,
                        exhausted ? 0 : backoffSeconds,
                        ex.getMessage()
                );
            }
        }
        return new ArtifactOutboxDispatchResponse(dispatched);
    }

    public ArtifactOutboxMetricsResponse metrics() {
        return new ArtifactOutboxMetricsResponse(
                countStatus("READY"),
                countStatus("PROCESSING"),
                countStatus("SENT"),
                countStatus("DEAD_LETTER"),
                countWhere("status = 'READY' and attempt_count > 0"),
                countWhere("status = 'DEAD_LETTER' and attempt_count >= " + MAX_ATTEMPTS)
        );
    }

    public List<ArtifactOutboxDeadLetterResponse> listDeadLetters(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbcTemplate.query("""
                select id, task_id, message_key, attempt_count, coalesce(last_error, '') as last_error,
                       dead_lettered_at
                from task_outbox
                where topic = ? and status = 'DEAD_LETTER'
                order by dead_lettered_at desc, created_at desc
                limit ?
                """, (rs, rowNum) -> new ArtifactOutboxDeadLetterResponse(
                rs.getString("id"),
                rs.getString("task_id"),
                rs.getString("message_key"),
                rs.getInt("attempt_count"),
                rs.getString("last_error"),
                rs.getTimestamp("dead_lettered_at") == null
                        ? Instant.EPOCH
                        : rs.getTimestamp("dead_lettered_at").toInstant()
        ), ARTIFACT_TOPIC, safeLimit);
    }

    @Transactional
    public void redrive(String outboxId) {
        List<String> taskIds = jdbcTemplate.queryForList("""
                select task_id from task_outbox
                where id = ? and topic = ? and status = 'DEAD_LETTER'
                """, String.class, outboxId, ARTIFACT_TOPIC);
        if (taskIds.isEmpty()) {
            throw new BusinessException("ARTIFACT_OUTBOX_DEAD_LETTER_NOT_FOUND", "死信记录不存在或不可重驱");
        }
        String taskId = taskIds.get(0);
        int updated = jdbcTemplate.update("""
                update task_outbox
                set status = 'READY', attempt_count = 0, last_error = null,
                    claimed_at = null, lease_owner = null, lease_until = null,
                    next_attempt_at = current_timestamp, dead_lettered_at = null
                where id = ? and topic = ? and status = 'DEAD_LETTER'
                """, outboxId, ARTIFACT_TOPIC);
        if (updated == 0) {
            throw new BusinessException("ARTIFACT_OUTBOX_DEAD_LETTER_NOT_FOUND", "死信记录不存在或不可重驱");
        }
        jdbcTemplate.update("""
                update task
                set task_status = 'PENDING', progress_phase = 'QUEUED', progress_message = '死信任务已人工重驱',
                    error_message = null, updated_by = 'SYSTEM:ARTIFACT_OUTBOX', updated_at = current_timestamp
                where id = ?
                """, taskId);
        jdbcTemplate.update("""
                update artifact_job set status = 'QUEUED', updated_at = current_timestamp
                where task_id = ?
                """, taskId);
        jdbcTemplate.update("""
                insert into task_event(id, task_id, event_type, message)
                values (?, ?, 'TASK_REDRIVEN', 'Artifact outbox 死信已人工重驱')
                """, Ids.newId(), taskId);
        redriveCounter.increment();
    }

    private int countStatus(String status) {
        return countWhere("status = '" + status + "'");
    }

    private int countWhere(String predicate) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from task_outbox where topic = ? and " + predicate,
                Integer.class,
                ARTIFACT_TOPIC
        );
        return count == null ? 0 : count;
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "artifact worker dispatch failed";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private record OutboxRow(
            String outboxId,
            String taskId,
            String messageKey,
            String payloadJson,
            int attemptCount
    ) {
    }
}
