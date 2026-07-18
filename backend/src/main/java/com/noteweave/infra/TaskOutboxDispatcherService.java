package com.noteweave.infra;

import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.common.BusinessException;
import com.noteweave.task.TaskService;
import com.noteweave.common.RequestContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TaskOutboxDispatcherService {

    private static final Logger log = LoggerFactory.getLogger(TaskOutboxDispatcherService.class);
    private static final Duration LEASE_DURATION = Duration.ofMinutes(1);
    private static final int MAX_ATTEMPTS = 5;

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final boolean enabled;
    private final List<String> topics;
    private final TaskService taskService;
    private final String dispatcherId = "task-outbox-" + UUID.randomUUID();
    private final MeterRegistry meterRegistry;

    public TaskOutboxDispatcherService(
            JdbcTemplate jdbcTemplate,
            KafkaTemplate<String, String> kafkaTemplate,
            NoteWeaveProperties properties,
            TaskService taskService
    ) {
        this(jdbcTemplate, kafkaTemplate, properties, taskService, new SimpleMeterRegistry());
    }

    @Autowired
    public TaskOutboxDispatcherService(
            JdbcTemplate jdbcTemplate,
            KafkaTemplate<String, String> kafkaTemplate,
            NoteWeaveProperties properties,
            TaskService taskService,
            MeterRegistry meterRegistry
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.enabled = properties.kafka().enabled();
        this.taskService = taskService;
        this.meterRegistry = meterRegistry;
        NoteWeaveProperties.Topics configured = properties.kafka().topics();
        this.topics = List.of(
                configured.sourceParse(), configured.sourceChunk(), configured.sourceIndex(),
                configured.wikiIngest(), configured.wikiRetract(), configured.generatedIngest(),
                configured.conversationSummary()
        );
    }

    @Scheduled(fixedDelayString = "${noteweave.kafka.outbox-redrive-delay-ms:1000}")
    public void dispatchReadyMessages() {
        if (!enabled) {
            return;
        }
        List<OutboxRow> rows = jdbcTemplate.query("""
                select id, task_id, topic, message_key, payload_json, attempt_count
                from task_outbox
                where topic in (?, ?, ?, ?, ?, ?, ?)
                  and attempt_count < ?
                  and ((status = 'READY' and (next_attempt_at is null or next_attempt_at <= current_timestamp))
                    or (status = 'PROCESSING' and (lease_until is null or lease_until < current_timestamp)))
                order by created_at, id
                limit 50
                """, (rs, rowNum) -> new OutboxRow(
                rs.getString("id"), rs.getString("task_id"), rs.getString("topic"),
                rs.getString("message_key"), rs.getString("payload_json"), rs.getInt("attempt_count")
        ), topics.get(0), topics.get(1), topics.get(2), topics.get(3), topics.get(4), topics.get(5), topics.get(6), MAX_ATTEMPTS);
        for (OutboxRow row : rows) {
            dispatch(row);
        }
    }

    private void dispatch(OutboxRow row) {
        MDC.put(RequestContext.EVENT_ID, row.id());
        MDC.put(RequestContext.CORRELATION_ID, row.id());
        if (row.taskId() != null) {
            MDC.put(RequestContext.TASK_ID, row.taskId());
        }
        try {
            dispatchInternal(row);
        } finally {
            MDC.clear();
        }
    }

    private void dispatchInternal(OutboxRow row) {
        int claimed = jdbcTemplate.update("""
                update task_outbox
                set status = 'PROCESSING', claimed_at = current_timestamp,
                    lease_owner = ?, lease_until = ?, attempt_count = attempt_count + 1, last_error = null
                where id = ? and attempt_count < ?
                  and ((status = 'READY' and (next_attempt_at is null or next_attempt_at <= current_timestamp))
                    or (status = 'PROCESSING' and (lease_until is null or lease_until < current_timestamp)))
                """, dispatcherId, Timestamp.from(Instant.now().plus(LEASE_DURATION)), row.id(), MAX_ATTEMPTS);
        if (claimed == 0) {
            return;
        }
        int attempt = row.attemptCount() + 1;
        try {
            kafkaTemplate.send(new ProducerRecord<>(row.topic(), row.messageKey(), row.payloadJson()))
                    .get(10, TimeUnit.SECONDS);
            jdbcTemplate.update("""
                    update task_outbox
                    set status = 'SENT', sent_at = current_timestamp, claimed_at = null,
                        lease_owner = null, lease_until = null, next_attempt_at = null, last_error = null
                    where id = ? and status = 'PROCESSING' and lease_owner = ?
                    """, row.id(), dispatcherId);
            meterRegistry.counter("noteweave.outbox.dispatch.success", "topic", row.topic()).increment();
        } catch (Exception ex) {
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
                    abbreviate(ex.getMessage()), row.id(), dispatcherId);
            if (exhausted && row.taskId() != null) {
                taskService.failTask(row.taskId(), "OUTBOX_DEAD_LETTER",
                        "Kafka Outbox 投递耗尽，已进入死信", "OUTBOX_DISPATCH_EXHAUSTED", true);
            }
            log.warn("Task outbox dispatch failed: outboxId={}, topic={}, attempt={}, exhausted={}",
                    row.id(), row.topic(), attempt, exhausted);
            meterRegistry.counter(exhausted ? "noteweave.outbox.dispatch.dead_letter" : "noteweave.outbox.dispatch.retry",
                    "topic", row.topic()).increment();
        }
    }

    private String abbreviate(String message) {
        String value = message == null ? "Kafka publish failed" : message;
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    public TaskOutboxMetricsResponse metrics() {
        return new TaskOutboxMetricsResponse(count("READY"), count("PROCESSING"), count("SENT"), count("DEAD_LETTER"));
    }

    public List<TaskOutboxDeadLetterResponse> listDeadLetters(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbcTemplate.query("""
                select id, task_id, topic, message_key, attempt_count, coalesce(last_error, '') last_error, dead_lettered_at
                from task_outbox where status = 'DEAD_LETTER' and topic in (?, ?, ?, ?, ?, ?, ?)
                order by dead_lettered_at desc, created_at desc limit ?
                """, (rs, rowNum) -> new TaskOutboxDeadLetterResponse(
                rs.getString("id"), rs.getString("task_id"), rs.getString("topic"), rs.getString("message_key"),
                rs.getInt("attempt_count"), rs.getString("last_error"),
                rs.getTimestamp("dead_lettered_at") == null ? Instant.EPOCH : rs.getTimestamp("dead_lettered_at").toInstant()
        ), topics.get(0), topics.get(1), topics.get(2), topics.get(3), topics.get(4), topics.get(5), topics.get(6), safeLimit);
    }

    public void redrive(String outboxId) {
        List<OutboxRow> rows = jdbcTemplate.query("""
                select id, task_id, topic, message_key, payload_json, attempt_count
                from task_outbox where id = ? and status = 'DEAD_LETTER'
                """, (rs, rowNum) -> new OutboxRow(rs.getString("id"), rs.getString("task_id"), rs.getString("topic"),
                rs.getString("message_key"), rs.getString("payload_json"), rs.getInt("attempt_count")), outboxId);
        if (rows.isEmpty() || !topics.contains(rows.get(0).topic())) {
            throw new BusinessException("TASK_OUTBOX_DEAD_LETTER_NOT_FOUND", "Outbox 死信不存在或不可重驱");
        }
        OutboxRow row = rows.get(0);
        jdbcTemplate.update("""
                update task_outbox set status = 'READY', attempt_count = 0, last_error = null,
                    claimed_at = null, lease_owner = null, lease_until = null,
                    next_attempt_at = current_timestamp, dead_lettered_at = null
                where id = ? and status = 'DEAD_LETTER'
                """, outboxId);
        if (row.taskId() != null) {
            taskService.redriveTask(row.taskId(), "noteweave.source.index".equals(row.topic()));
        }
    }

    private int count(String status) {
        Integer value = jdbcTemplate.queryForObject("""
                select count(*) from task_outbox where status = ? and topic in (?, ?, ?, ?, ?, ?, ?)
                """, Integer.class, status, topics.get(0), topics.get(1), topics.get(2), topics.get(3), topics.get(4), topics.get(5), topics.get(6));
        return value == null ? 0 : value;
    }

    private record OutboxRow(String id, String taskId, String topic, String messageKey, String payloadJson,
                             int attemptCount) {
    }
}
