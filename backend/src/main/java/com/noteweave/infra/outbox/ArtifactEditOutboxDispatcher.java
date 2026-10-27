package com.noteweave.infra.outbox;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * C4 v2 产物编辑事件的专用持久投递器（FB-03，N-05 裁决）。
 *
 * <p>事件由 A 在编辑版本事务内写入 artifact_edit_event（业务事件存储，合同见
 * contracts-ready）；本投递器认领 READY/租约过期 IN_FLIGHT 行，发布到专用
 * artifact-edit topic，成功置 SENT。认领/退避/确认协议与 DurableOutboxDispatcher
 * 同款：一次性 claim_token、有界重试、达上限 DEAD_LETTER（保留人工重驱身份），
 * 但不经过 task_outbox——编辑事件不是任务，不伪造 taskId，也不回写 originTask 失败。
 *
 * <p>投递与 Memory 业务回执分离：发送成功仍可能重复（至少一次），消费者按
 * workspace+editEventId 幂等（B-08/FB-04 回执状态机）。
 * 事件终态不回写原生成任务：编辑是发布后事实，生成任务的成败与此无关。
 */
@Service
public class ArtifactEditOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ArtifactEditOutboxDispatcher.class);

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MeterRegistry meterRegistry;
    private final String topic;
    private final boolean enabled;
    private final int batchSize;
    private final long leaseSeconds;
    private final int maxAttempts;
    private final long maxBackoffSeconds;
    private final String instanceId = "artifact-edit-outbox-" + UUID.randomUUID();

    public ArtifactEditOutboxDispatcher(
            JdbcTemplate jdbcTemplate,
            KafkaTemplate<String, String> kafkaTemplate,
            MeterRegistry meterRegistry,
            @Value("${noteweave.kafka.topics.artifact-edit:noteweave.artifact.edit}") String topic,
            @Value("${noteweave.kafka.enabled:true}") boolean kafkaEnabled,
            @Value("${noteweave.artifact-edit.outbox-enabled:true}") boolean outboxEnabled,
            @Value("${noteweave.artifact-edit.dispatch-batch-size:20}") int batchSize,
            @Value("${noteweave.artifact-edit.dispatch-lease-seconds:120}") long leaseSeconds,
            @Value("${noteweave.artifact-edit.dispatch-max-attempts:10}") int maxAttempts,
            @Value("${noteweave.artifact-edit.dispatch-max-backoff-seconds:300}") long maxBackoffSeconds
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
        this.topic = topic;
        this.enabled = kafkaEnabled && outboxEnabled;
        this.batchSize = Math.max(1, batchSize);
        this.leaseSeconds = Math.max(5, leaseSeconds);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.maxBackoffSeconds = Math.max(1, maxBackoffSeconds);
    }

    @Scheduled(fixedDelayString = "${noteweave.artifact-edit.dispatch-delay-ms:5000}")
    public void dispatchDue() {
        if (!enabled) {
            return;
        }
        List<String> due = jdbcTemplate.queryForList("""
                select id from artifact_edit_event
                where (status = 'READY' and (next_attempt_at is null or next_attempt_at <= current_timestamp))
                   or (status = 'IN_FLIGHT' and (lease_until is null or lease_until < current_timestamp))
                order by created_at, id
                limit ?
                """, String.class, batchSize);
        for (String eventId : due) {
            dispatchOne(eventId);
        }
    }

    private void dispatchOne(String eventId) {
        String claimToken = Ids.newId();
        int claimed = jdbcTemplate.update("""
                update artifact_edit_event
                set status = 'IN_FLIGHT', claim_token = ?, lease_owner = ?,
                    lease_until = timestampadd(second, ?, current_timestamp),
                    attempt_count = attempt_count + 1, next_attempt_at = null, updated_at = current_timestamp
                where id = ?
                  and (
                    (status = 'READY'
                        and (next_attempt_at is null or next_attempt_at <= current_timestamp)
                        and attempt_count < ?)
                    or (status = 'IN_FLIGHT'
                        and (lease_until is null or lease_until < current_timestamp)
                        and attempt_count < ?)
                  )
                """, claimToken, instanceId, leaseSeconds, eventId, maxAttempts, maxAttempts);
        if (claimed != 1) {
            // 竞争失败或重试预算已尽：交给死信收敛
            deadLetterIfExhausted(eventId, "CLAIM_LOST");
            return;
        }
        Integer attempts = jdbcTemplate.queryForObject(
                "select attempt_count from artifact_edit_event where id = ?", Integer.class, eventId);
        String payloadJson = jdbcTemplate.queryForObject(
                "select event_json from artifact_edit_event where id = ?", String.class, eventId);
        String messageKey = jdbcTemplate.queryForObject(
                "select workspace_id from artifact_edit_event where id = ?", String.class, eventId);
        try {
            kafkaTemplate.send(new ProducerRecord<>(topic, messageKey, payloadJson)).get(10, TimeUnit.SECONDS);
            jdbcTemplate.update("""
                    update artifact_edit_event
                    set status = 'SENT', sent_at = current_timestamp, lease_owner = null, lease_until = null,
                        claim_token = null, next_attempt_at = null, last_error = null, updated_at = current_timestamp
                    where id = ? and claim_token = ? and status = 'IN_FLIGHT'
                    """, eventId, claimToken);
            meterRegistry.counter("noteweave.artifact-edit.outcome", "outcome", "SENT").increment();
        } catch (Exception failure) {
            int attempt = attempts == null ? 1 : attempts;
            long backoff = Math.min(maxBackoffSeconds, (1L << Math.min(8, attempt)) * 5L);
            String error = abbreviate(String.valueOf(failure.getMessage()), 500);
            jdbcTemplate.update("""
                    update artifact_edit_event
                    set status = 'READY', lease_owner = null, lease_until = null, claim_token = null,
                        next_attempt_at = timestampadd(second, ?, current_timestamp),
                        last_error = ?, updated_at = current_timestamp
                    where id = ? and claim_token = ? and status = 'IN_FLIGHT'
                    """, backoff, error, eventId, claimToken);
            log.warn("Artifact edit event dispatch failed: eventId={}, attempt={}, reason={}",
                    eventId, attempt, error);
            meterRegistry.counter("noteweave.artifact-edit.outcome", "outcome", "RETRY").increment();
            deadLetterIfExhausted(eventId, "DISPATCH_RETRY");
        }
    }

    /** 重试预算耗尽：DEAD_LETTER 持久终态，保留人工恢复身份（redrive 入口见控制器）。 */
    private void deadLetterIfExhausted(String eventId, String errorCode) {
        int moved = jdbcTemplate.update("""
                update artifact_edit_event
                set status = 'DEAD_LETTER', lease_owner = null, lease_until = null, claim_token = null,
                    next_attempt_at = null, updated_at = current_timestamp,
                    last_error = coalesce(last_error, ?)
                where id = ? and status in ('READY', 'IN_FLIGHT') and attempt_count >= ?
                """, errorCode, eventId, maxAttempts);
        if (moved == 1) {
            log.error("Artifact edit event dead-lettered: eventId={}, attempts={}", eventId, maxAttempts);
            meterRegistry.counter("noteweave.artifact-edit.outcome", "outcome", "DEAD_LETTER").increment();
        }
    }

    /**
     * 人工重驱死信：同一事件行换新投递代次，attempt 重计，恢复身份写入 last_error。
     * 不修改事件载荷与摘要（不可变）；消费端按 editEventId 幂等，重复投递安全。
     */
    public RedriveOutcome redrive(String eventId, String actor, String reason) {
        if (actor == null || actor.isBlank() || reason == null || reason.isBlank()) {
            throw new BusinessException("ARTIFACT_EDIT_REDRIVE_IDENTITY_REQUIRED",
                    "人工重驱必须携带 actor 与 reason，保留可信恢复身份");
        }
        Integer generation = jdbcTemplate.queryForObject("""
                select delivery_generation from artifact_edit_event where id = ? and status = 'DEAD_LETTER'
                """, Integer.class, eventId);
        if (generation == null) {
            throw new BusinessException("ARTIFACT_EDIT_DEAD_LETTER_NOT_FOUND", "编辑事件死信不存在或不可重驱");
        }
        int newGeneration = generation + 1;
        int moved = jdbcTemplate.update("""
                update artifact_edit_event
                set status = 'READY', attempt_count = 0, next_attempt_at = current_timestamp,
                    lease_owner = null, lease_until = null, claim_token = null,
                    delivery_generation = ?, updated_at = current_timestamp,
                    last_error = ?
                where id = ? and status = 'DEAD_LETTER' and delivery_generation = ?
                """, newGeneration, abbreviate("redriven by " + actor + ": " + reason, 500),
                eventId, generation);
        if (moved != 1) {
            throw new BusinessException("ARTIFACT_EDIT_REDRIVE_CONFLICT", "编辑事件死信已被其他重驱处理");
        }
        log.info("Artifact edit event redriven: eventId={}, actor={}, oldGeneration={}, newGeneration={}",
                eventId, actor, generation, newGeneration);
        return new RedriveOutcome(eventId, generation, newGeneration);
    }

    /** 投递视角指标：各状态计数与最老未决年龄。 */
    public java.util.Map<String, Long> metrics() {
        java.util.Map<String, Long> counts = new java.util.LinkedHashMap<>();
        jdbcTemplate.query("""
                select status, count(*) as total,
                       coalesce(max(timestampdiff(second, created_at, current_timestamp)), 0) as oldest_age
                from artifact_edit_event group by status
                """, rs -> {
            counts.put("artifact-edit." + rs.getString("status") + ".count", rs.getLong("total"));
            counts.put("artifact-edit." + rs.getString("status") + ".oldest_age_seconds", rs.getLong("oldest_age"));
        });
        return counts;
    }

    public List<DeadLetterView> listDeadLetters(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbcTemplate.query("""
                select id, workspace_id, artifact_job_id, request_id, attempt_count,
                       coalesce(last_error, '') last_error, updated_at
                from artifact_edit_event where status = 'DEAD_LETTER'
                order by updated_at desc limit ?
                """, (rs, rowNum) -> new DeadLetterView(
                rs.getString("id"), rs.getString("workspace_id"), rs.getString("artifact_job_id"),
                rs.getString("request_id"), rs.getInt("attempt_count"), rs.getString("last_error"),
                rs.getTimestamp("updated_at") == null ? java.time.Instant.EPOCH
                        : rs.getTimestamp("updated_at").toInstant()),
                safeLimit);
    }

    private String abbreviate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 人工重驱结果：旧/新投递代次。 */
    public record RedriveOutcome(String eventId, int oldDeliveryGeneration, int newDeliveryGeneration) { }

    /** 死信视图：不含事件正文，只有身份与失败摘要。 */
    public record DeadLetterView(
            String eventId, String workspaceId, String artifactJobId, String requestId,
            int attemptCount, String lastError, java.time.Instant updatedAt) { }
}
