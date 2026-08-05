package com.noteweave.worker;

import java.time.Instant;
import java.util.List;
import com.noteweave.common.BusinessException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import com.noteweave.common.Ids;
import com.noteweave.infra.outbox.DurableOutboxDispatcher;
import com.noteweave.infra.outbox.OutboxDispatchPolicy;
import com.noteweave.task.TaskService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArtifactOutboxDispatcherService {

    private static final String ARTIFACT_TOPIC = "noteweave.artifact.job";

    private final JdbcTemplate jdbcTemplate;
    private final ArtifactOutboxPublisher artifactOutboxPublisher;
    private final TaskService taskService;
    private final DurableOutboxDispatcher outboxDispatcher;
    private final Counter dispatchedCounter;
    private final Counter failedCounter;
    private final Counter deadLetterCounter;
    private final Counter redriveCounter;

    public ArtifactOutboxDispatcherService(
            JdbcTemplate jdbcTemplate,
            ArtifactOutboxPublisher artifactOutboxPublisher,
            TaskService taskService,
            MeterRegistry meterRegistry,
            DurableOutboxDispatcher outboxDispatcher
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.artifactOutboxPublisher = artifactOutboxPublisher;
        this.taskService = taskService;
        this.outboxDispatcher = outboxDispatcher;
        this.dispatchedCounter = meterRegistry.counter("noteweave.artifact.outbox.dispatched");
        this.failedCounter = meterRegistry.counter("noteweave.artifact.outbox.failed");
        this.deadLetterCounter = meterRegistry.counter("noteweave.artifact.outbox.dead_lettered");
        this.redriveCounter = meterRegistry.counter("noteweave.artifact.outbox.redriven");
    }

    public ArtifactOutboxDispatchResponse dispatchReadyArtifactJobs(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 20));
        DurableOutboxDispatcher.DispatchResult result = outboxDispatcher.dispatchTaskMessages(
                new DurableOutboxDispatcher.TopicPolicy(
                        List.of(ARTIFACT_TOPIC),
                        OutboxDispatchPolicy.ARTIFACT_LEASE_DURATION,
                        DurableOutboxDispatcher.CompletionMode.LEASED_UNTIL_CALLBACK,
                        20
                ),
                safeLimit,
                message -> artifactOutboxPublisher.publish(
                        message.topic(),
                        message.messageKey(),
                        message.payloadJson(),
                        message.deliveryToken()
                ),
                message -> {
                    taskService.failTask(
                            message.taskId(),
                            "OUTBOX_DEAD_LETTER",
                            "Artifact Worker 投递连续失败，已进入死信队列",
                            "ARTIFACT_DISPATCH_EXHAUSTED",
                            true
                    );
                    jdbcTemplate.update("""
                            update artifact_job set status = 'FAILED', updated_at = current_timestamp
                            where task_id = ?
                            """, message.taskId());
                }
        );
        if (result.publishedCount() > 0) {
            dispatchedCounter.increment(result.publishedCount());
        }
        if (result.failedCount() > 0) {
            failedCounter.increment(result.failedCount());
        }
        if (result.deadLetteredCount() > 0) {
            deadLetterCounter.increment(result.deadLetteredCount());
        }
        return new ArtifactOutboxDispatchResponse(result.publishedCount());
    }

    public ArtifactOutboxMetricsResponse metrics() {
        return new ArtifactOutboxMetricsResponse(
                countStatus("READY"),
                countStatus("PROCESSING"),
                countStatus("SENT"),
                countStatus("DEAD_LETTER"),
                countWhere("status = 'READY' and attempt_count > 0"),
                countWhere("status = 'DEAD_LETTER' and attempt_count >= " + OutboxDispatchPolicy.MAX_ATTEMPTS)
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
}
