package com.noteweave.infra.outbox;

import com.noteweave.common.RequestContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns the durable claim, fencing, retry, and dead-letter state machine for outbox delivery. */
@Component
public class DurableOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DurableOutboxDispatcher.class);
    public static final String DELIVERY_TOKEN_HEADER = "X-NoteWeave-Outbox-Delivery-Token";

    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate transactionTemplate;
    private final String dispatcherId = "outbox-dispatcher-" + UUID.randomUUID();

    public DurableOutboxDispatcher(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this(jdbcTemplate, meterRegistry, null);
    }

    @Autowired
    public DurableOutboxDispatcher(
            JdbcTemplate jdbcTemplate,
            MeterRegistry meterRegistry,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry == null ? new SimpleMeterRegistry() : meterRegistry;
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    public DispatchResult dispatchTaskMessages(
            TopicPolicy policy,
            int requestedLimit,
            MessagePublisher publisher,
            DeadLetterHandler deadLetterHandler
    ) {
        Objects.requireNonNull(policy, "policy");
        MessagePublisher requiredPublisher = Objects.requireNonNull(publisher, "publisher");
        DeadLetterHandler requiredDeadLetterHandler = deadLetterHandler == null ? message -> { } : deadLetterHandler;
        int exhaustedLeases = deadLetterExpiredExhaustedTaskLeases(
                policy, requestedLimit, requiredDeadLetterHandler
        );
        DispatchResult dispatched = dispatch(
                findTaskCandidates(policy, requestedLimit),
                policy.leaseDuration(),
                policy.completionMode(),
                requiredPublisher,
                requiredDeadLetterHandler
        );
        return new DispatchResult(
                dispatched.publishedCount(),
                dispatched.failedCount() + exhaustedLeases,
                dispatched.deadLetteredCount() + exhaustedLeases
        );
    }

    public DispatchResult dispatchAgentCommands(
            String runId,
            int requestedLimit,
            MessagePublisher publisher
    ) {
        return dispatchAgentCommands(runId, requestedLimit, publisher, message -> { });
    }

    public DispatchResult dispatchAgentCommands(
            String runId,
            int requestedLimit,
            MessagePublisher publisher,
            DeadLetterHandler deadLetterHandler
    ) {
        return dispatch(
                findAgentCandidates(runId, requestedLimit),
                OutboxDispatchPolicy.LEASE_DURATION,
                CompletionMode.SENT,
                Objects.requireNonNull(publisher, "publisher"),
                Objects.requireNonNull(deadLetterHandler, "deadLetterHandler")
        );
    }

    private DispatchResult dispatch(
            List<Candidate> candidates,
            Duration leaseDuration,
            CompletionMode completionMode,
            MessagePublisher publisher,
            DeadLetterHandler deadLetterHandler
    ) {
        int published = 0;
        int failed = 0;
        int deadLettered = 0;
        for (Candidate candidate : candidates) {
            putMdc(candidate);
            try {
                String deliveryToken = dispatcherId + ":" + UUID.randomUUID();
                if (!claim(candidate, deliveryToken, leaseDuration)) {
                    continue;
                }
                try {
                    publisher.publish(candidate.message(deliveryToken));
                } catch (Exception failure) {
                    if (failure instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    FailureOutcome outcome = recordFailure(
                            candidate, deliveryToken, failure, deadLetterHandler
                    );
                    if (outcome != FailureOutcome.LOST_CLAIM) {
                        failed++;
                        if (outcome == FailureOutcome.DEAD_LETTERED) {
                            deadLettered++;
                        }
                    }
                    continue;
                }
                if (complete(
                        candidate,
                        deliveryToken,
                        completionMode,
                        leaseDuration
                )) {
                    published++;
                    meterRegistry.counter(
                            "noteweave.outbox.dispatch.success", "topic", candidate.topic()
                    ).increment();
                } else {
                    log.warn("Outbox claim was lost after publish: outboxId={}, topic={}",
                            candidate.id(), candidate.topic());
                }
            } finally {
                MDC.clear();
            }
        }
        return new DispatchResult(published, failed, deadLettered);
    }

    /**
     * Closes the terminal-state gap for callback-held messages. A worker can accept delivery without
     * ever calling back; after the last lease expires, attempt_count has reached MAX_ATTEMPTS and the
     * normal candidate query intentionally excludes the row. Without this sweep it stays PROCESSING
     * forever and never invokes the business dead-letter finalizer.
     */
    private int deadLetterExpiredExhaustedTaskLeases(
            TopicPolicy policy,
            int requestedLimit,
            DeadLetterHandler deadLetterHandler
    ) {
        int limit = boundedLimit(requestedLimit, policy.maxBatchSize());
        String placeholders = String.join(", ", java.util.Collections.nCopies(policy.topics().size(), "?"));
        String sql = """
                select id, task_id, topic, message_key, payload_json, attempt_count
                from task_outbox
                where topic in (%s)
                  and status = 'PROCESSING'
                  and attempt_count >= ?
                  and (lease_until is null or lease_until < current_timestamp)
                order by created_at, id
                limit ?
                """.formatted(placeholders);
        List<Object> arguments = new ArrayList<>(policy.topics());
        arguments.add(OutboxDispatchPolicy.MAX_ATTEMPTS);
        arguments.add(limit);
        List<Candidate> exhausted = jdbcTemplate.query(sql, (rs, rowNum) -> new Candidate(
                Store.TASK,
                rs.getString("id"),
                rs.getString("task_id"),
                rs.getString("topic"),
                rs.getString("message_key"),
                rs.getString("payload_json"),
                rs.getInt("attempt_count"),
                0
        ), arguments.toArray());

        int deadLettered = 0;
        for (Candidate candidate : exhausted) {
            boolean transitioned = inTransaction(() -> {
                int updated = jdbcTemplate.update("""
                        update task_outbox
                        set status = 'DEAD_LETTER', claimed_at = null, lease_owner = null,
                            lease_until = null, next_attempt_at = null,
                            dead_lettered_at = current_timestamp,
                            last_error = 'outbox callback lease expired after maximum delivery attempts'
                        where id = ? and status = 'PROCESSING' and attempt_count = ?
                          and attempt_count >= ?
                          and (lease_until is null or lease_until < current_timestamp)
                        """, candidate.id(), candidate.attemptCount(), OutboxDispatchPolicy.MAX_ATTEMPTS);
                if (updated == 0) {
                    return false;
                }
                deadLetterHandler.onDeadLetter(candidate.message(""));
                return true;
            });
            if (transitioned) {
                deadLettered++;
                meterRegistry.counter(
                        "noteweave.outbox.dispatch.dead_letter", "topic", candidate.topic()
                ).increment();
                log.warn("Expired outbox callback lease was dead-lettered: outboxId={}, topic={}, attempts={}",
                        candidate.id(), candidate.topic(), candidate.attemptCount());
            }
        }
        return deadLettered;
    }

    private List<Candidate> findTaskCandidates(TopicPolicy policy, int requestedLimit) {
        int limit = boundedLimit(requestedLimit, policy.maxBatchSize());
        String placeholders = String.join(", ", java.util.Collections.nCopies(policy.topics().size(), "?"));
        String sql = """
                select id, task_id, topic, message_key, payload_json, attempt_count
                from task_outbox
                where topic in (%s)
                  and attempt_count < ?
                  and ((status = 'READY' and (next_attempt_at is null or next_attempt_at <= current_timestamp))
                    or (status = 'PROCESSING' and (lease_until is null or lease_until < current_timestamp)))
                order by created_at, id
                limit ?
                """.formatted(placeholders);
        List<Object> arguments = new ArrayList<>(policy.topics());
        arguments.add(OutboxDispatchPolicy.MAX_ATTEMPTS);
        arguments.add(limit);
        return jdbcTemplate.query(sql, (rs, rowNum) -> new Candidate(
                Store.TASK,
                rs.getString("id"),
                rs.getString("task_id"),
                rs.getString("topic"),
                rs.getString("message_key"),
                rs.getString("payload_json"),
                rs.getInt("attempt_count"),
                0
        ), arguments.toArray());
    }

    private List<Candidate> findAgentCandidates(String runId, int requestedLimit) {
        int limit = boundedLimit(requestedLimit, 100);
        boolean scoped = runId != null && !runId.isBlank();
        String runFilter = scoped ? "and rao.research_run_id = ?" : "";
        String sql = """
                select rao.id, rao.research_agent_task_id, rao.delivery_no, rao.topic, rao.message_key,
                       rao.payload_json, rao.attempt_count
                from research_agent_outbox rao
                join research_run rr on rr.id = rao.research_run_id
                join research_agent_task rat on rat.id = rao.research_agent_task_id
                where ((rao.status = 'READY'
                          and (rao.next_attempt_at is null or rao.next_attempt_at <= current_timestamp))
                    or (rao.status = 'PROCESSING'
                          and (rao.lease_until is null or rao.lease_until < current_timestamp)))
                  and rao.attempt_count < ?
                  %s
                  and rr.agent_execution_mode = 'INCREMENTAL_V1'
                  and rr.status not in ('COMPLETED', 'FAILED', 'CANCELLED')
                  and (rat.status in ('PENDING', 'EXPIRED')
                    or (rat.status = 'RETRY_WAIT'
                          and (rat.next_attempt_at is null or rat.next_attempt_at <= current_timestamp)))
                order by rat.priority_score desc, rat.wave_no, rao.created_at, rao.id
                limit ?
                """.formatted(runFilter);
        List<Object> arguments = new ArrayList<>();
        arguments.add(OutboxDispatchPolicy.MAX_ATTEMPTS);
        if (scoped) {
            arguments.add(runId);
        }
        arguments.add(limit);
        return jdbcTemplate.query(sql, (rs, rowNum) -> new Candidate(
                Store.AGENT,
                rs.getString("id"),
                rs.getString("research_agent_task_id"),
                rs.getString("topic"),
                rs.getString("message_key"),
                rs.getString("payload_json"),
                rs.getInt("attempt_count"),
                rs.getInt("delivery_no")
        ), arguments.toArray());
    }

    public boolean ownsTaskMessage(String topic, String taskId, String deliveryToken) {
        if (blank(topic) || blank(taskId) || blank(deliveryToken)) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from task_outbox
                where topic = ? and task_id = ? and lease_owner = ?
                  and ((status = 'PROCESSING' and lease_until >= current_timestamp)
                    or status = 'SENT')
                """, Integer.class, topic, taskId, deliveryToken);
        return count != null && count == 1;
    }

    public boolean renewTaskMessage(
            String topic,
            String taskId,
            String deliveryToken,
            Duration leaseDuration
    ) {
        if (blank(topic) || blank(taskId) || blank(deliveryToken)
                || leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            return false;
        }
        return jdbcTemplate.update("""
                update task_outbox set lease_until = timestampadd(second, ?, current_timestamp)
                where topic = ? and task_id = ? and status = 'PROCESSING'
                  and lease_owner = ? and lease_until >= current_timestamp
                """, Math.max(1L, leaseDuration.toSeconds()), topic, taskId, deliveryToken) == 1;
    }

    public boolean acknowledgeTaskMessage(String topic, String taskId, String deliveryToken) {
        if (blank(topic) || blank(taskId) || blank(deliveryToken)) {
            return false;
        }
        return jdbcTemplate.update("""
                update task_outbox
                set status = 'SENT', sent_at = current_timestamp, claimed_at = null,
                    lease_until = null, next_attempt_at = null, last_error = null
                where topic = ? and task_id = ? and status = 'PROCESSING' and lease_owner = ?
                """, topic, taskId, deliveryToken) == 1;
    }

    public String activeTaskDeliveryToken(String topic, String taskId) {
        if (blank(topic) || blank(taskId)) {
            return "";
        }
        List<String> tokens = jdbcTemplate.queryForList("""
                select lease_owner from task_outbox
                where topic = ? and task_id = ? and status = 'PROCESSING'
                  and lease_owner is not null and lease_until >= current_timestamp
                order by claimed_at desc, id desc limit 1
                """, String.class, topic, taskId);
        return tokens.isEmpty() ? "" : tokens.get(0);
    }

    private boolean claim(Candidate candidate, String deliveryToken, Duration leaseDuration) {
        long leaseSeconds = Math.max(1L, leaseDuration.toSeconds());
        if (candidate.store() == Store.TASK) {
            return jdbcTemplate.update("""
                    update task_outbox
                    set status = 'PROCESSING', claimed_at = current_timestamp,
                        lease_owner = ?, lease_until = timestampadd(second, ?, current_timestamp),
                        attempt_count = attempt_count + 1,
                        next_attempt_at = null, last_error = null
                    where id = ? and attempt_count = ? and attempt_count < ?
                      and ((status = 'READY'
                              and (next_attempt_at is null or next_attempt_at <= current_timestamp))
                        or (status = 'PROCESSING'
                              and (lease_until is null or lease_until < current_timestamp)))
                    """, deliveryToken, leaseSeconds, candidate.id(), candidate.attemptCount(),
                    OutboxDispatchPolicy.MAX_ATTEMPTS) == 1;
        }
        return jdbcTemplate.update("""
                update research_agent_outbox
                set status = 'PROCESSING', lease_owner = ?,
                    lease_until = timestampadd(second, ?, current_timestamp),
                    attempt_count = attempt_count + 1, next_attempt_at = null,
                    last_error = null, updated_at = current_timestamp
                where id = ? and delivery_no = ? and attempt_count = ? and attempt_count < ?
                  and ((status = 'READY'
                          and (next_attempt_at is null or next_attempt_at <= current_timestamp))
                    or (status = 'PROCESSING'
                          and (lease_until is null or lease_until < current_timestamp)))
                """, deliveryToken, leaseSeconds, candidate.id(), candidate.deliveryNo(),
                candidate.attemptCount(), OutboxDispatchPolicy.MAX_ATTEMPTS) == 1;
    }

    private boolean complete(
            Candidate candidate,
            String deliveryToken,
            CompletionMode completionMode,
            Duration leaseDuration
    ) {
        if (candidate.store() == Store.AGENT) {
            return jdbcTemplate.update("""
                    update research_agent_outbox
                    set status = 'SENT', sent_at = current_timestamp,
                        lease_owner = null, lease_until = null, updated_at = current_timestamp
                    where id = ? and delivery_no = ? and status = 'PROCESSING' and lease_owner = ?
                    """, candidate.id(), candidate.deliveryNo(), deliveryToken) == 1;
        }
        if (completionMode == CompletionMode.LEASED_UNTIL_CALLBACK) {
            return jdbcTemplate.update("""
                    update task_outbox
                    set last_error = null, lease_until = timestampadd(second, ?, current_timestamp)
                    where id = ? and status = 'PROCESSING' and lease_owner = ?
                    """, Math.max(1L, leaseDuration.toSeconds()), candidate.id(), deliveryToken) == 1;
        }
        return jdbcTemplate.update("""
                update task_outbox
                set status = 'SENT', sent_at = current_timestamp, claimed_at = null,
                    lease_owner = null, lease_until = null, next_attempt_at = null, last_error = null
                where id = ? and status = 'PROCESSING' and lease_owner = ?
                """, candidate.id(), deliveryToken) == 1;
    }

    private FailureOutcome recordFailure(
            Candidate candidate,
            String deliveryToken,
            Exception failure,
            DeadLetterHandler deadLetterHandler
    ) {
        int attempt = candidate.attemptCount() + 1;
        boolean exhausted = attempt >= OutboxDispatchPolicy.MAX_ATTEMPTS;
        FailureOutcome outcome = inTransaction(() -> {
            int updated = candidate.store() == Store.TASK
                    ? recordTaskFailure(candidate, deliveryToken, failure, attempt, exhausted)
                    : recordAgentFailure(candidate, deliveryToken, failure, attempt, exhausted);
            if (updated == 0) {
                return FailureOutcome.LOST_CLAIM;
            }
            if (exhausted) {
                deadLetterHandler.onDeadLetter(candidate.message(deliveryToken));
                return FailureOutcome.DEAD_LETTERED;
            }
            return FailureOutcome.RETRY_SCHEDULED;
        });
        if (outcome != FailureOutcome.LOST_CLAIM) {
            meterRegistry.counter(
                    outcome == FailureOutcome.DEAD_LETTERED
                            ? "noteweave.outbox.dispatch.dead_letter"
                            : "noteweave.outbox.dispatch.retry",
                    "topic", candidate.topic()
            ).increment();
            log.warn("Outbox dispatch failed: outboxId={}, topic={}, attempt={}, outcome={}",
                    candidate.id(), candidate.topic(), attempt, outcome, failure);
        }
        return outcome;
    }

    private int recordTaskFailure(
            Candidate candidate,
            String deliveryToken,
            Exception failure,
            int attempt,
            boolean exhausted
    ) {
        String schedule = exhausted
                ? "next_attempt_at = null, dead_lettered_at = current_timestamp"
                : "next_attempt_at = timestampadd(second, ?, current_timestamp), dead_lettered_at = null";
        String sql = """
                update task_outbox
                set status = ?, claimed_at = null, lease_owner = null, lease_until = null,
                    %s, last_error = ?
                where id = ? and status = 'PROCESSING' and lease_owner = ?
                """.formatted(schedule);
        if (exhausted) {
            return jdbcTemplate.update(sql, "DEAD_LETTER",
                    OutboxDispatchPolicy.abbreviate(failure.getMessage(), "outbox publish failed"),
                    candidate.id(), deliveryToken);
        }
        return jdbcTemplate.update(sql, "READY", OutboxDispatchPolicy.retryDelaySeconds(attempt),
                OutboxDispatchPolicy.abbreviate(failure.getMessage(), "outbox publish failed"),
                candidate.id(), deliveryToken);
    }

    private int recordAgentFailure(
            Candidate candidate,
            String deliveryToken,
            Exception failure,
            int attempt,
            boolean exhausted
    ) {
        String schedule = exhausted
                ? "next_attempt_at = null, dead_lettered_at = current_timestamp"
                : "next_attempt_at = timestampadd(second, ?, current_timestamp), dead_lettered_at = null";
        String sql = """
                update research_agent_outbox
                set status = ?, lease_owner = null, lease_until = null,
                    %s, last_error = ?,
                    updated_at = current_timestamp
                where id = ? and delivery_no = ? and status = 'PROCESSING' and lease_owner = ?
                """.formatted(schedule);
        if (exhausted) {
            return jdbcTemplate.update(sql, "DEAD_LETTER",
                    OutboxDispatchPolicy.abbreviate(failure.getMessage(), "outbox publish failed"),
                    candidate.id(), candidate.deliveryNo(), deliveryToken);
        }
        return jdbcTemplate.update(sql, "READY", OutboxDispatchPolicy.retryDelaySeconds(attempt),
                OutboxDispatchPolicy.abbreviate(failure.getMessage(), "outbox publish failed"),
                candidate.id(), candidate.deliveryNo(), deliveryToken);
    }

    private <T> T inTransaction(java.util.concurrent.Callable<T> operation) {
        if (transactionTemplate == null) {
            return call(operation);
        }
        return transactionTemplate.execute(status -> call(operation));
    }

    private <T> T call(java.util.concurrent.Callable<T> operation) {
        try {
            return operation.call();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Outbox state transition failed", failure);
        }
    }

    private void putMdc(Candidate candidate) {
        MDC.put(RequestContext.EVENT_ID, candidate.id());
        MDC.put(RequestContext.CORRELATION_ID, candidate.id());
        if (candidate.taskId() != null) {
            MDC.put(RequestContext.TASK_ID, candidate.taskId());
        }
    }

    private int boundedLimit(int requested, int maximum) {
        return Math.max(1, Math.min(requested, maximum));
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public enum CompletionMode {
        SENT,
        LEASED_UNTIL_CALLBACK
    }

    public record TopicPolicy(
            List<String> topics,
            Duration leaseDuration,
            CompletionMode completionMode,
            int maxBatchSize
    ) {
        public TopicPolicy {
            topics = List.copyOf(Objects.requireNonNull(topics, "topics"));
            if (topics.isEmpty() || topics.stream().anyMatch(topic -> topic == null || topic.isBlank())) {
                throw new IllegalArgumentException("At least one non-blank outbox topic is required");
            }
            if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
                throw new IllegalArgumentException("Outbox lease duration must be positive");
            }
            completionMode = Objects.requireNonNull(completionMode, "completionMode");
            if (maxBatchSize < 1) {
                throw new IllegalArgumentException("Outbox max batch size must be positive");
            }
        }
    }

    public record Message(
            String outboxId,
            String taskId,
            String topic,
            String messageKey,
            String payloadJson,
            int attemptNo,
            String deliveryToken
    ) { }

    public record DispatchResult(int publishedCount, int failedCount, int deadLetteredCount) { }

    @FunctionalInterface
    public interface MessagePublisher {
        void publish(Message message) throws Exception;
    }

    @FunctionalInterface
    public interface DeadLetterHandler {
        void onDeadLetter(Message message);
    }

    private enum Store {
        TASK,
        AGENT
    }

    private enum FailureOutcome {
        RETRY_SCHEDULED,
        DEAD_LETTERED,
        LOST_CLAIM
    }

    private record Candidate(
            Store store,
            String id,
            String taskId,
            String topic,
            String messageKey,
            String payloadJson,
            int attemptCount,
            int deliveryNo
    ) {
        private Message message(String deliveryToken) {
            return new Message(
                    id,
                    taskId,
                    topic,
                    messageKey,
                    payloadJson,
                    attemptCount + 1,
                    deliveryToken
            );
        }
    }
}
