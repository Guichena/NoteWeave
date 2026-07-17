package com.noteweave.worker;

import com.noteweave.task.TaskService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ResearchOutboxDispatcherService {

    private static final String RESEARCH_TOPIC = "noteweave.research.run";
    private static final int MAX_ATTEMPTS = 5;

    private final JdbcTemplate jdbcTemplate;
    private final ResearchOutboxPublisher researchOutboxPublisher;
    private final TaskService taskService;
    private final String dispatcherId = "research-dispatcher-" + UUID.randomUUID();

    public ResearchOutboxDispatcherService(
            JdbcTemplate jdbcTemplate,
            ResearchOutboxPublisher researchOutboxPublisher,
            TaskService taskService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.researchOutboxPublisher = researchOutboxPublisher;
        this.taskService = taskService;
    }

    public ResearchOutboxDispatchResponse dispatchReadyResearchRuns(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 20));
        List<OutboxRow> rows = jdbcTemplate.query("""
                select id, task_id, message_key, payload_json, attempt_count
                from task_outbox
                where topic = ? and attempt_count < ?
                  and ((status = 'READY' and (next_attempt_at is null or next_attempt_at <= current_timestamp))
                    or (status = 'PROCESSING' and (lease_until is null or lease_until < current_timestamp)))
                order by created_at asc, id asc
                limit ?
                """, (rs, rowNum) -> new OutboxRow(
                rs.getString("id"),
                rs.getString("task_id"),
                rs.getString("message_key"),
                rs.getString("payload_json"),
                rs.getInt("attempt_count")
        ), RESEARCH_TOPIC, MAX_ATTEMPTS, safeLimit);

        int dispatched = 0;
        for (OutboxRow row : rows) {
            int claimed = jdbcTemplate.update("""
                    update task_outbox
                    set status = 'PROCESSING', claimed_at = current_timestamp,
                        lease_owner = ?, lease_until = ?, attempt_count = attempt_count + 1,
                        last_error = null
                    where id = ? and attempt_count < ?
                      and ((status = 'READY' and (next_attempt_at is null or next_attempt_at <= current_timestamp))
                        or (status = 'PROCESSING' and (lease_until is null or lease_until < current_timestamp)))
                    """, dispatcherId, Timestamp.from(Instant.now().plusSeconds(60)),
                    row.outboxId(), MAX_ATTEMPTS);
            if (claimed == 0) {
                continue;
            }
            try {
                researchOutboxPublisher.publish(RESEARCH_TOPIC, row.messageKey(), row.payloadJson());
                int sent = jdbcTemplate.update("""
                        update task_outbox
                        set status = 'SENT', sent_at = current_timestamp, claimed_at = null,
                            lease_owner = null, lease_until = null, next_attempt_at = null, last_error = null
                        where id = ? and status = 'PROCESSING' and lease_owner = ?
                        """, row.outboxId(), dispatcherId);
                dispatched += sent;
            } catch (RuntimeException ex) {
                int attempt = row.attemptCount() + 1;
                boolean exhausted = attempt >= MAX_ATTEMPTS;
                jdbcTemplate.update("""
                        update task_outbox
                        set status = ?, claimed_at = null, lease_owner = null, lease_until = null,
                            next_attempt_at = ?, dead_lettered_at = ?, last_error = ?
                        where id = ? and status = 'PROCESSING' and lease_owner = ?
                        """,
                        exhausted ? "DEAD_LETTER" : "READY",
                        exhausted ? null : Timestamp.from(Instant.now().plusSeconds(Math.min(60L, 1L << attempt))),
                        exhausted ? Timestamp.from(Instant.now()) : null,
                        abbreviate(ex.getMessage()), row.outboxId(), dispatcherId);
                if (exhausted) {
                    taskService.failTask(row.taskId(), "OUTBOX_DEAD_LETTER",
                            "Research Kafka 投递耗尽，已进入死信", "RESEARCH_DISPATCH_EXHAUSTED", true);
                }
            }
        }
        return new ResearchOutboxDispatchResponse(dispatched);
    }

    private String abbreviate(String value) {
        String message = value == null ? "Research Kafka publish failed" : value;
        return message.length() <= 1000 ? message : message.substring(0, 1000);
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
