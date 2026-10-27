package com.noteweave.memory;

import com.noteweave.common.FinalizationOutcome;
import com.noteweave.common.Ids;
import com.noteweave.infra.outbox.OutboxBusinessFinalizer;
import com.noteweave.task.TaskOutboxEnqueuer;
import com.noteweave.task.TaskService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Memory 抽取与隐式反馈的分阶段持久处理回执（B-05，M-MEMORY-RETRY）。
 *
 * <p>回执身份 = workspace + message + processorVersion 范围内的 workspace + message。
 * 阶段机：EXTRACT_PENDING → EXTRACT_DONE → FEEDBACK_PENDING → COMPLETED，
 * 每个阶段独立有界重试（RETRY_WAIT(stage)），达到上限进入 TERMINAL_FAILURE(stage)，
 * 不假装完成。空候选是有效的抽取完成；不能用已有 memory_signal 行数判断处理完成。
 *
 * <p>模型调用永远在数据库事务外；回执、任务与重投消息在同一短事务提交。
 * 恢复扫描按页认领租约，进程重启后仍能发现待重试回执；
 * 每次重投显式递增 delivery_generation，与统一入队的语义幂等配合。
 */
@Service
public class MemoryProcessingReceiptService {

    public static final String STAGE_EXTRACT = "EXTRACT";
    public static final String STAGE_FEEDBACK = "FEEDBACK";
    public static final String STAGE_NONE = "NONE";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RETRY_WAIT = "RETRY_WAIT";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_TERMINAL_FAILURE = "TERMINAL_FAILURE";

    /** 抽取与反馈的重试上限分开计数，一个阶段耗尽不吞掉另一阶段已完成的结果。 */
    static final int MAX_STAGE_ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(MemoryProcessingReceiptService.class);
    /** 处理器版本：阶段机或判定语义变化时递增，避免旧回执被新语义覆盖解释。 */
    static final String PROCESSOR_VERSION = "2026-10-24.1";

    private final JdbcTemplate jdbcTemplate;
    private final TaskService taskService;
    private final TaskOutboxEnqueuer enqueuer;
    private final TransactionTemplate transactions;
    private final String topic;
    private final String instanceId = "memory-receipts-" + UUID.randomUUID();
    private final int scanBatchSize;
    private final long retryBackoffSeconds;
    private final int maxRedeliveries;

    public MemoryProcessingReceiptService(
            JdbcTemplate jdbcTemplate,
            TaskService taskService,
            TaskOutboxEnqueuer enqueuer,
            PlatformTransactionManager transactionManager,
            @Value("${noteweave.kafka.topics.memory-extraction:noteweave.memory.extraction}") String topic,
            @Value("${noteweave.memory.receipt-scan-batch-size:50}") int scanBatchSize,
            @Value("${noteweave.memory.receipt-retry-backoff-seconds:120}") long retryBackoffSeconds,
            @Value("${noteweave.memory.receipt-max-redeliveries:5}") int maxRedeliveries
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.taskService = taskService;
        this.enqueuer = enqueuer;
        this.transactions = new TransactionTemplate(transactionManager);
        this.topic = topic;
        this.scanBatchSize = Math.max(1, scanBatchSize);
        this.retryBackoffSeconds = Math.max(5L, retryBackoffSeconds);
        this.maxRedeliveries = Math.max(1, maxRedeliveries);
    }

    /**
     * 入队事务内建立回执（B-05）：消息首次进入处理时登记 EXTRACT_PENDING；
     * 已有回执时返回既有状态，旧任务失败不能永远挡住恢复。
     */
    public Receipt ensurePending(String workspaceId, String messageId) {
        List<Receipt> existing = load(workspaceId, messageId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        jdbcTemplate.update("""
                insert into memory_processing_receipt(
                    id, workspace_id, message_id, processor_version, status, stage,
                    extract_attempts, feedback_attempts, delivery_generation, feedback_applicable,
                    created_at, updated_at)
                values (?, ?, ?, ?, 'PENDING', 'EXTRACT', 0, 0, 1, 1, current_timestamp, current_timestamp)
                """, Ids.newId(), workspaceId, messageId, PROCESSOR_VERSION);
        return load(workspaceId, messageId).get(0);
    }

    public List<Receipt> load(String workspaceId, String messageId) {
        return jdbcTemplate.query("""
                select id, workspace_id, message_id, task_id, status, stage,
                       extract_attempts, feedback_attempts, delivery_generation, feedback_applicable,
                       extract_method, next_attempt_at, redelivery_failures
                from memory_processing_receipt
                where workspace_id = ? and message_id = ?
                """, MemoryProcessingReceiptService::mapReceipt,
                workspaceId, messageId);
    }

    private static Receipt mapReceipt(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Receipt(
                rs.getString("id"), rs.getString("workspace_id"), rs.getString("message_id"),
                rs.getString("task_id"), rs.getString("status"), rs.getString("stage"),
                rs.getInt("extract_attempts"), rs.getInt("feedback_attempts"),
                rs.getInt("delivery_generation"), rs.getInt("feedback_applicable") == 1,
                rs.getString("extract_method"),
                rs.getTimestamp("next_attempt_at") == null ? null : rs.getTimestamp("next_attempt_at").toInstant(),
                rs.getInt("redelivery_failures"));
    }

    /** 绑定任务：入队创建任务后在同一事务写回。 */
    public void bindTask(String workspaceId, String messageId, String taskId) {
        jdbcTemplate.update("""
                update memory_processing_receipt
                set task_id = ?, updated_at = current_timestamp
                where workspace_id = ? and message_id = ?
                """, taskId, workspaceId, messageId);
    }

    /**
     * 抽取完成（含空候选）：进入 FEEDBACK_PENDING；候选 ID 有界保留供对账。
     * FB-08：截断只丢弃完整元素，绝不截出残缺 JSON——恢复输入必须始终可解析，
     * 解析失败不能被当成 EXTRACT_DONE 空候选。
     */
    public boolean markExtractDone(String workspaceId, String messageId, String method, List<String> candidateIds) {
        String candidates = boundedCandidatesJson(candidateIds);
        return jdbcTemplate.update("""
                update memory_processing_receipt
                set status = 'PENDING', stage = 'FEEDBACK', extract_method = ?,
                    candidates_json = ?, last_error_code = null, updated_at = current_timestamp
                where workspace_id = ? and message_id = ? and stage = 'EXTRACT'
                """, method, candidates, workspaceId, messageId) == 1;
    }

    /** 候选 ID 列表序列化：整体超限时按整元素丢弃，结果永远是合法完整 JSON 数组。 */
    static String boundedCandidatesJson(List<String> candidateIds) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            return "[]";
        }
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String full = com.noteweave.common.Json.write(mapper, candidateIds);
        if (full.length() <= 2000) {
            return full;
        }
        List<String> bounded = new java.util.ArrayList<>(candidateIds);
        while (bounded.size() > 1
                && com.noteweave.common.Json.write(mapper, bounded).length() > 2000) {
            bounded.remove(bounded.size() - 1);
        }
        if (bounded.isEmpty()) {
            return "[]";
        }
        String json = com.noteweave.common.Json.write(mapper, bounded);
        return json.length() <= 2000 ? json : "[]";
    }

    /** 反馈不适用（无需 follow-up 或没有可判定对象）：直接完成。 */
    public void markFeedbackNotApplicable(String workspaceId, String messageId) {
        jdbcTemplate.update("""
                update memory_processing_receipt
                set status = 'COMPLETED', stage = 'NONE', feedback_applicable = 0,
                    next_attempt_at = null, completed_at = current_timestamp, updated_at = current_timestamp
                where workspace_id = ? and message_id = ? and stage = 'FEEDBACK'
                """, workspaceId, messageId);
    }

    /** 全部阶段完成。 */
    public void markCompleted(String workspaceId, String messageId) {
        jdbcTemplate.update("""
                update memory_processing_receipt
                set status = 'COMPLETED', stage = 'NONE', next_attempt_at = null,
                    completed_at = current_timestamp, updated_at = current_timestamp
                where workspace_id = ? and message_id = ?
                """, workspaceId, messageId);
    }

    /**
     * 阶段性可重试失败：按阶段独立累计次数，未达上限进入 RETRY_WAIT 并安排退避；
     * 达到上限进入 TERMINAL_FAILURE，保留 stage 与原因供查询告警。
     */
    public void scheduleRetry(String workspaceId, String messageId, String stage, String errorCode) {
        String attemptColumn = STAGE_FEEDBACK.equals(stage) ? "feedback_attempts" : "extract_attempts";
        Integer attempts = jdbcTemplate.queryForObject("""
                select %s from memory_processing_receipt where workspace_id = ? and message_id = ?
                """.formatted(attemptColumn), Integer.class, workspaceId, messageId);
        int next = (attempts == null ? 0 : attempts) + 1;
        if (next >= MAX_STAGE_ATTEMPTS) {
            jdbcTemplate.update("""
                    update memory_processing_receipt
                    set status = 'TERMINAL_FAILURE', stage = ?, next_attempt_at = null,
                        last_error_code = ?, completed_at = current_timestamp, updated_at = current_timestamp
                    where workspace_id = ? and message_id = ?
                    """, stage, errorCode, workspaceId, messageId);
            return;
        }
        jdbcTemplate.update("""
                update memory_processing_receipt
                set status = 'RETRY_WAIT', stage = ?, %s = ?, next_attempt_at = timestampadd(second, ?, current_timestamp),
                    last_error_code = ?, updated_at = current_timestamp
                where workspace_id = ? and message_id = ?
                """.formatted(attemptColumn), stage, next, retryBackoffSeconds, errorCode, workspaceId, messageId);
    }

    /** 消费死信收尾（补偿适配器入口）：按回执安排有界重试；没有回执的旧消息按过期跳过。 */
    public FinalizationOutcome finalizeDeadLettered(String workspaceId, String messageId, String errorCode) {
        List<Receipt> receipts = load(workspaceId, messageId);
        if (receipts.isEmpty()) {
            return FinalizationOutcome.IGNORED_STALE;
        }
        Receipt receipt = receipts.get(0);
        if (STATUS_COMPLETED.equals(receipt.status())) {
            return FinalizationOutcome.APPLIED;
        }
        if (STATUS_TERMINAL_FAILURE.equals(receipt.status())) {
            return FinalizationOutcome.IGNORED_STALE;
        }
        transactions.executeWithoutResult(status -> {
            scheduleRetry(workspaceId, messageId, receipt.stage(), errorCode);
            if (receipt.taskId() != null) {
                taskService.failTask(receipt.taskId(), "MEMORY_EXTRACTION_FAILED",
                        "记忆候选提取消费失败，已进入死信", errorCode, true);
            }
        });
        return FinalizationOutcome.APPLIED;
    }

    /**
     * 恢复扫描：分页认领到期 RETRY_WAIT 回执，重驱任务并显式递增 delivery_generation
     * 重投消息；进程重启后仍能发现待恢复项。抖动退避避免与消费高峰叠加。
     */
    @Scheduled(fixedDelayString = "${noteweave.memory.receipt-scan-delay-ms:30000}")
    public void scanDue() {
        List<Receipt> due = jdbcTemplate.query("""
                select id, workspace_id, message_id, task_id, status, stage,
                       extract_attempts, feedback_attempts, delivery_generation, feedback_applicable,
                       extract_method, next_attempt_at, redelivery_failures
                from memory_processing_receipt
                where status = 'RETRY_WAIT'
                  and next_attempt_at is not null and next_attempt_at <= current_timestamp
                order by next_attempt_at, id
                limit ?
                """, MemoryProcessingReceiptService::mapReceipt, scanBatchSize);
        for (Receipt receipt : due) {
            try {
                redeliver(receipt);
            } catch (RuntimeException failure) {
                log.warn("记忆处理回执重投失败: workspace={}, message={}, reason={}",
                        receipt.workspaceId(), receipt.messageId(), failure.getMessage());
                // MEM-02：失败的条目必须后退并计数，否则它会一直占着批次队头，新回执永远轮不到
                transactions.executeWithoutResult(status -> postpone(receipt, "MEMORY_RECEIPT_REDELIVERY_FAILED"));
            }
        }
    }

    /** 单条重投：任务恢复、回执换代与新消息在同一事务提交。 */
    private void redeliver(Receipt receipt) {
        transactions.executeWithoutResult(status -> {
            String taskId = receipt.taskId();
            if (taskId == null || taskId.isBlank()) {
                // 旧数据没有任务绑定：直接按终态失败登记，等待人工处理
                terminalFailure(receipt, "MEMORY_RECEIPT_TASK_MISSING");
                return;
            }
            // MEM-02：先看任务状态再决定。非 FAILED 的任务直接 redrive 会抛异常，
            // 事务回滚后回执原地不动，scanDue 每轮都会重新捞到它，队头就被坏回执占满了。
            String taskStatus = taskStatus(taskId);
            if (taskStatus == null) {
                terminalFailure(receipt, "MEMORY_RECEIPT_TASK_MISSING");
                return;
            }
            switch (taskStatus) {
                case "FAILED" -> { }
                case "CANCELLED" -> {
                    terminalFailure(receipt, "MEMORY_RECEIPT_TASK_CANCELLED");
                    return;
                }
                case "COMPLETED" -> {
                    terminalFailure(receipt, "MEMORY_RECEIPT_TASK_COMPLETED");
                    return;
                }
                case "PENDING", "RUNNING" -> {
                    // 任务还没结束：不动它，退避后再看；同时计入失败次数，避免无限打转
                    postpone(receipt, "MEMORY_RECEIPT_TASK_NOT_FAILED");
                    return;
                }
                default -> {
                    terminalFailure(receipt, "MEMORY_RECEIPT_TASK_UNEXPECTED");
                    return;
                }
            }
            int nextGeneration = receipt.deliveryGeneration() + 1;
            int moved = jdbcTemplate.update("""
                    update memory_processing_receipt
                    set delivery_generation = ?, next_attempt_at = null, redelivery_failures = 0,
                        updated_at = current_timestamp
                    where id = ? and status = 'RETRY_WAIT' and delivery_generation = ?
                    """, nextGeneration, receipt.id(), receipt.deliveryGeneration());
            if (moved != 1) {
                return;
            }
            taskService.redriveTask(taskId, false);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("task_id", taskId);
            payload.put("workspace_id", receipt.workspaceId());
            payload.put("message_id", receipt.messageId());
            enqueuer.enqueue(new TaskOutboxEnqueuer.EnqueueCommand(
                    receipt.workspaceId(), taskId, topic, receipt.messageId(),
                    "MEMORY_EXTRACTION", receipt.messageId(), null,
                    String.valueOf(nextGeneration), "MEMORY_EXTRACT", null, nextGeneration, payload));
        });
    }

    private String taskStatus(String taskId) {
        List<String> rows = jdbcTemplate.queryForList(
                "select task_status from task where id = ?", String.class, taskId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 回执进入明确终态：不再参与重投，保留原因供查询与告警。 */
    private void terminalFailure(Receipt receipt, String errorCode) {
        jdbcTemplate.update("""
                update memory_processing_receipt
                set status = 'TERMINAL_FAILURE', next_attempt_at = null, last_error_code = ?,
                    completed_at = current_timestamp, updated_at = current_timestamp
                where id = ? and status = 'RETRY_WAIT'
                """, errorCode, receipt.id());
    }

    /** 重投没成功：计数并把下一次尝试推到队尾；到上限就转终态，不再占用扫描批次。 */
    private void postpone(Receipt receipt, String errorCode) {
        int failures = receipt.redeliveryFailures() + 1;
        if (failures >= maxRedeliveries) {
            jdbcTemplate.update("""
                    update memory_processing_receipt
                    set status = 'TERMINAL_FAILURE', next_attempt_at = null, redelivery_failures = ?,
                        last_error_code = ?, completed_at = current_timestamp, updated_at = current_timestamp
                    where id = ? and status = 'RETRY_WAIT'
                    """, failures, errorCode, receipt.id());
            return;
        }
        jdbcTemplate.update("""
                update memory_processing_receipt
                set redelivery_failures = ?, last_error_code = ?,
                    next_attempt_at = timestampadd(second, ?, current_timestamp), updated_at = current_timestamp
                where id = ? and status = 'RETRY_WAIT'
                """, failures, errorCode, redeliveryBackoffSeconds(failures), receipt.id());
    }

    /** 失败越多次退避越久（上限 1 小时），保证坏回执不会长期停在扫描顺序的最前面。 */
    private long redeliveryBackoffSeconds(int failures) {
        return Math.min(3600L, retryBackoffSeconds * Math.max(1, failures));
    }

    /** 回执快照：阶段机当前状态，供抽取服务判定续跑位置。 */
    public record Receipt(
            String id, String workspaceId, String messageId, String taskId,
            String status, String stage,
            int extractAttempts, int feedbackAttempts, int deliveryGeneration,
            boolean feedbackApplicable, String extractMethod, Instant nextAttemptAt,
            int redeliveryFailures
    ) {
        public boolean completed() {
            return STATUS_COMPLETED.equals(status);
        }

        public boolean terminalFailure() {
            return STATUS_TERMINAL_FAILURE.equals(status);
        }
    }

    /** 向 outbox 补偿执行器注册的消费死信收尾适配器。 */
    @org.springframework.stereotype.Component
    static class MemoryExtractionFinalizer implements OutboxBusinessFinalizer {

        public static final String KIND = "MEMORY_EXTRACTION";

        private final MemoryProcessingReceiptService receipts;

        MemoryExtractionFinalizer(MemoryProcessingReceiptService receipts) {
            this.receipts = receipts;
        }

        @Override
        public String businessKind() {
            return KIND;
        }

        @Override
        public FinalizationOutcome finalize(Map<String, Object> identity,
                com.noteweave.common.FinalizationFence fence) {
            String workspaceId = identity.get("workspaceId") == null
                    ? String.valueOf(identity.get("workspace_id")) : String.valueOf(identity.get("workspaceId"));
            String messageId = identity.get("messageId") == null
                    ? String.valueOf(identity.get("message_id")) : String.valueOf(identity.get("messageId"));
            if (workspaceId == null || messageId == null || "null".equals(workspaceId) || "null".equals(messageId)) {
                return FinalizationOutcome.IGNORED_STALE;
            }
            return receipts.finalizeDeadLettered(workspaceId, messageId,
                    identity.get("errorCode") == null ? "MEMORY_EXTRACTION_FAILED"
                            : String.valueOf(identity.get("errorCode")));
        }
    }
}
