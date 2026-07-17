package com.noteweave.research;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MA4B coordinator-owned reaping and cancellation; workers never mutate lifecycle state directly. */
@Service
public class ResearchAgentLifecycleService {

    private final JdbcTemplate jdbcTemplate;
    private final ResearchAgentCommandOutboxService outboxService;
    private final ResearchBudgetAndCheckpointService budgetService;

    public ResearchAgentLifecycleService(JdbcTemplate jdbcTemplate, ResearchAgentCommandOutboxService outboxService,
                                         ResearchBudgetAndCheckpointService budgetService) {
        this.jdbcTemplate = jdbcTemplate;
        this.outboxService = outboxService;
        this.budgetService = budgetService;
    }

    @Transactional
    public ReapReceipt reapExpiredLeases() {
        int retryWaiting = 0;
        int failed = 0;
        List<String> runIds = jdbcTemplate.query("""
                select distinct research_run_id from research_agent_task
                where status in ('CLAIMED', 'RUNNING')
                  and lease_expires_at is not null and lease_expires_at <= current_timestamp
                order by research_run_id
                """, (rs, rowNum) -> rs.getString(1));
        for (String runId : runIds) {
            jdbcTemplate.query("select id from research_run where id = ? for update",
                    rs -> rs.next() ? rs.getString(1) : null, runId);
            List<ExpiredTask> expired = jdbcTemplate.query("""
                    select id, research_run_id, attempt_count, max_attempts
                    from research_agent_task
                    where research_run_id = ? and status in ('CLAIMED', 'RUNNING')
                      and lease_expires_at is not null and lease_expires_at <= current_timestamp
                    order by id for update
                    """, (rs, rowNum) -> new ExpiredTask(
                    rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4)), runId);
            for (ExpiredTask task : expired) {
                if (task.attemptCount() >= task.maxAttempts()) {
                    int updated = jdbcTemplate.update("""
                            update research_agent_task set status = 'FAILED', terminal_reason = 'LEASE_RETRY_EXHAUSTED',
                            terminal_at = current_timestamp, worker_instance_id = null, lease_expires_at = null,
                            updated_at = current_timestamp
                            where id = ? and status in ('CLAIMED', 'RUNNING')
                              and lease_expires_at is not null and lease_expires_at <= current_timestamp
                            """, task.taskId());
                    if (updated == 1) {
                        releaseReservationsForTask(task.taskId());
                        releaseCellBindings(task.taskId());
                        failed++;
                    }
                    continue;
                }
                int updated = jdbcTemplate.update("""
                        update research_agent_task
                        set status = 'RETRY_WAIT',
                            next_attempt_at = timestampadd(second, ?, current_timestamp),
                            worker_instance_id = null, lease_expires_at = null,
                            updated_at = current_timestamp
                        where id = ? and status in ('CLAIMED', 'RUNNING')
                          and lease_expires_at is not null and lease_expires_at <= current_timestamp
                        """, backoffSeconds(task.attemptCount()), task.taskId());
                if (updated == 1) {
                    releaseCellBindings(task.taskId());
                    outboxService.enqueueRetry(task.taskId());
                    retryWaiting++;
                }
            }
        }
        return new ReapReceipt(retryWaiting, failed);
    }

    @Transactional
    public CancelReceipt cancelRun(String runId, String reason) {
        String status = jdbcTemplate.query("select status from research_run where id = ? for update",
                rs -> rs.next() ? rs.getString(1) : null, runId);
        if (status == null) throw new BusinessException("RESEARCH_AGENT_RUN_NOT_FOUND", "Research run does not exist");
        if ("CANCELLED".equals(status)) return new CancelReceipt(0, true);
        if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
            throw new BusinessException("RESEARCH_AGENT_RUN_TERMINAL", "Cannot cancel terminal research run");
        }
        jdbcTemplate.query("""
                select id from research_agent_task where research_run_id = ? order by id for update
                """, (rs, rowNum) -> rs.getString(1), runId);
        jdbcTemplate.update("update research_run set status = 'CANCELLED', updated_at = current_timestamp where id = ?", runId);
        int cancelled = jdbcTemplate.update("""
                update research_agent_task set status = 'CANCELLED', terminal_reason = ?, cancelled_at = current_timestamp,
                terminal_at = current_timestamp, worker_instance_id = null, lease_expires_at = null, updated_at = current_timestamp
                where research_run_id = ? and status not in ('SUBMITTED', 'FAILED', 'CANCELLED')
                """, normalizeReason(reason), runId);
        releaseReservationsForRun(runId);
        jdbcTemplate.query("""
                select id from research_cell
                where research_run_id = ? and active_task_id is not null
                order by cast(cell_key as binary), id for update
                """, (rs, rowNum) -> rs.getString(1), runId);
        jdbcTemplate.update("""
                update research_cell set active_task_id = null, updated_at = current_timestamp
                where research_run_id = ? and active_task_id is not null
                """, runId);
        jdbcTemplate.update("""
                update research_agent_outbox set status = 'CANCELLED', updated_at = current_timestamp
                where research_run_id = ? and status = 'READY'
                """, runId);
        return new CancelReceipt(cancelled, false);
    }

    @Transactional
    public DeliveryFailureReceipt recordDeliveryFailure(DeliveryFailureCommand command) {
        requireTaskForRun(command.researchRunId(), command.taskId());
        requireMatchingOutbox(command);
        String existing = jdbcTemplate.query("""
                select id from research_agent_delivery_failure where research_agent_task_id = ? and failure_key = ?
                """, rs -> rs.next() ? rs.getString(1) : null, command.taskId(), command.failureKey());
        if (existing != null) return new DeliveryFailureReceipt(existing, true);
        String id = Ids.newId();
        jdbcTemplate.update("""
                insert into research_agent_delivery_failure(
                    id, research_run_id, research_agent_task_id, research_agent_outbox_id, failure_key,
                    reason_code, trace_digest, delivery_attempt, redrive_status
                ) values (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                """, id, command.researchRunId(), command.taskId(), blankToNull(command.outboxId()), command.failureKey(),
                bounded(command.reasonCode(), 128), bounded(command.traceDigest(), 128), Math.max(1, command.deliveryAttempt()));
        return new DeliveryFailureReceipt(id, false);
    }

    @Transactional
    public RedriveReceipt redriveDeliveryFailure(String failureId) {
        FailureRow failure = jdbcTemplate.query("""
                select id, research_agent_task_id, redrive_status from research_agent_delivery_failure where id = ? for update
                """, rs -> rs.next() ? new FailureRow(rs.getString(1), rs.getString(2), rs.getString(3)) : null, failureId);
        if (failure == null) throw new BusinessException("RESEARCH_AGENT_DELIVERY_FAILURE_NOT_FOUND", "Delivery failure does not exist");
        if ("REDRIVEN".equals(failure.redriveStatus())) return new RedriveReceipt(failure.id(), true);
        outboxService.enqueueRetry(failure.taskId());
        jdbcTemplate.update("update research_agent_delivery_failure set redrive_status = 'REDRIVEN', redriven_at = current_timestamp where id = ?", failure.id());
        return new RedriveReceipt(failure.id(), false);
    }

    private long backoffSeconds(int attemptCount) {
        return Math.min(60L, 1L << Math.min(6, Math.max(0, attemptCount - 1)));
    }

    private String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? "CANCELLED" : reason.trim().substring(0, Math.min(128, reason.trim().length()));
    }

    private void releaseReservationsForRun(String runId) {
        reservationScopes("research_run_id = ?", runId).forEach(scope ->
                budgetService.releaseForLifecycle(scope.runId(), scope.taskId(), scope.reservationId()));
    }

    private void releaseReservationsForTask(String taskId) {
        reservationScopes("research_agent_task_id = ?", taskId).forEach(scope ->
                budgetService.releaseForLifecycle(scope.runId(), scope.taskId(), scope.reservationId()));
    }

    private void releaseCellBindings(String taskId) {
        TaskBindingScope scope = jdbcTemplate.query("""
                select research_run_id, quorum_group_key, candidate_quorum
                from research_agent_task where id = ?
                """, rs -> rs.next()
                ? new TaskBindingScope(rs.getString(1), rs.getString(2), rs.getInt(3))
                : null, taskId);
        if (scope != null && scope.candidateQuorum() == 2 && scope.quorumGroupKey() != null) {
            Integer activeSiblingCount = jdbcTemplate.queryForObject("""
                    select count(*) from research_agent_task
                    where research_run_id = ? and quorum_group_key = ?
                      and status not in ('SUBMITTED', 'FAILED', 'CANCELLED')
                    """, Integer.class, scope.runId(), scope.quorumGroupKey());
            if (activeSiblingCount != null && activeSiblingCount > 0) return;
            jdbcTemplate.query("""
                    select id from research_cell
                    where research_run_id = ? and active_task_id = ?
                    order by cast(cell_key as binary), id for update
                    """, (rs, rowNum) -> rs.getString(1), scope.runId(), scope.quorumGroupKey());
            jdbcTemplate.update("""
                    update research_cell set active_task_id = null, updated_at = current_timestamp
                    where research_run_id = ? and active_task_id = ?
                    """, scope.runId(), scope.quorumGroupKey());
            return;
        }
        jdbcTemplate.query("""
                select id from research_cell where active_task_id = ?
                order by cast(cell_key as binary), id for update
                """, (rs, rowNum) -> rs.getString(1), taskId);
        jdbcTemplate.update("update research_cell set active_task_id = null, updated_at = current_timestamp where active_task_id = ?", taskId);
    }

    private List<ReservationScope> reservationScopes(String predicate, String value) {
        return jdbcTemplate.query("select id, research_run_id, research_agent_task_id from research_budget_reservation where " + predicate
                        + " and state = 'RESERVED' and agent_completion_id is null order by id",
                (rs, rowNum) -> new ReservationScope(rs.getString(1), rs.getString(2), rs.getString(3)), value);
    }

    private void requireTaskForRun(String runId, String taskId) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from research_agent_task where id = ? and research_run_id = ?",
                Integer.class, taskId, runId);
        if (count == null || count != 1) throw new BusinessException("RESEARCH_AGENT_TASK_NOT_FOUND", "Research agent task does not exist for run");
    }

    private void requireMatchingOutbox(DeliveryFailureCommand command) {
        if (command.outboxId() == null || command.outboxId().isBlank()) return;
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from research_agent_outbox
                where id = ? and research_run_id = ? and research_agent_task_id = ? and delivery_no = ?
                """, Integer.class, command.outboxId().trim(), command.researchRunId(), command.taskId(),
                Math.max(1, command.deliveryAttempt()));
        if (count == null || count != 1) {
            throw new BusinessException("RESEARCH_AGENT_DELIVERY_FAILURE_OUTBOX_INVALID",
                    "Delivery failure outbox identity does not match task delivery");
        }
    }

    private String bounded(String value, int maxLength) {
        if (value == null || value.isBlank()) throw new BusinessException("RESEARCH_AGENT_DELIVERY_FAILURE_INVALID", "Failure contract is invalid");
        String normalized = value.trim();
        return normalized.substring(0, Math.min(maxLength, normalized.length()));
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public record ReapReceipt(int retryWaitingCount, int failedCount) { }
    public record CancelReceipt(int cancelledTaskCount, boolean idempotentReplay) { }
    public record DeliveryFailureCommand(String researchRunId, String taskId, String outboxId, String failureKey,
                                         String reasonCode, String traceDigest, int deliveryAttempt) { }
    public record DeliveryFailureReceipt(String failureId, boolean idempotentReplay) { }
    public record RedriveReceipt(String failureId, boolean idempotentReplay) { }
    private record ExpiredTask(String taskId, String runId, int attemptCount, int maxAttempts) { }
    private record ReservationScope(String reservationId, String runId, String taskId) { }
    private record TaskBindingScope(String runId, String quorumGroupKey, int candidateQuorum) { }
    private record FailureRow(String id, String taskId, String redriveStatus) { }
}
