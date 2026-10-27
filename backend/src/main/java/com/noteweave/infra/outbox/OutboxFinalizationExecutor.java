package com.noteweave.infra.outbox;

import com.noteweave.common.BusinessException;
import com.noteweave.common.FinalizationFence;
import com.noteweave.common.FinalizationOutcome;
import com.noteweave.retrieval.projection.RetrievalProjectionDeadLetterFinalizer;
import com.noteweave.retrieval.projection.SourceRetrievalProjectionFinalizer;
import com.noteweave.source.SourceParseFailureFinalizer;
import com.noteweave.source.SourceTopicNames;
import com.noteweave.task.TaskService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * outbox 死信补偿的独立执行器（B-01 / FB-01）。
 *
 * <p>Dispatcher 只负责投递事实：DEAD_LETTER 终止与 PENDING 补偿同一短事务提交后，
 * 由本 worker 认领租约、核对业务代次并调用业务 finalizer。业务 finalizer 持有自身
 * 状态机且必须幂等：补偿在「业务已提交、结果未写回」时退出会重复执行，只有数据库
 * 能证明已提交才标记 APPLIED。
 *
 * <p>FB-01：每条补偿在调用业务 finalizer 时携带 {@link FinalizationFence}，
 * finalizer 在更新业务状态前必须于同一短事务内复核围栏：
 * <ul>
 *   <li>TASK_OUTBOX store：锁定并核对该 outbox 行仍是 DEAD_LETTER 且代次未变；
 *       人工重驱换代后旧代次补偿只能无副作用退出；</li>
 *   <li>KAFKA_DLT store：DLT offset 身份不等于当前投递代次，以
 *       「登记时间之后没有同任务 TASK_REDRIVEN 事件」作为当前业务阶段围栏；</li>
 *   <li>AGENT_OUTBOX store：由注册的 finalizer 自行核对 deliveryNo（Research 已实现）。</li>
 * </ul>
 *
 * <p>未知 business_kind 不再静默按 IGNORED_STALE 消失：进入 TERMINAL_FAILURE 并输出
 * 结构化告警，保留人工恢复入口（重新登记或修复 kind 后重驱）。
 */
@Service
public class OutboxFinalizationExecutor {

    public static final String KIND_SOURCE_PARSE = "SOURCE_PARSE";
    public static final String KIND_RETRIEVAL_PROJECTION = "RETRIEVAL_PROJECTION";
    public static final String KIND_TASK_FALLBACK = "TASK_FALLBACK";
    public static final String KIND_KAFKA_DLT_PARSE = "KAFKA_DLT_PARSE";
    public static final String KIND_KAFKA_DLT_PROJECTION = "KAFKA_DLT_PROJECTION";
    /** MEM-01：记忆抽取死信补偿，由 {@code MemoryProcessingReceiptService.MemoryExtractionFinalizer} 执行。 */
    public static final String KIND_MEMORY_EXTRACTION = "MEMORY_EXTRACTION";

    /** 已知补偿类型：指标 kind 标签只允许有限枚举，未知类型统一记 unknown（FB-09）。 */
    private static final Set<String> KNOWN_KINDS = Set.of(
            KIND_SOURCE_PARSE, KIND_RETRIEVAL_PROJECTION, KIND_TASK_FALLBACK,
            KIND_KAFKA_DLT_PARSE, KIND_KAFKA_DLT_PROJECTION, KIND_MEMORY_EXTRACTION, "RESEARCH_AGENT_COMMAND");

    private static final Logger log = LoggerFactory.getLogger(OutboxFinalizationExecutor.class);

    private final OutboxFinalizationRepository finalizations;
    private final SourceParseFailureFinalizer parseFailureFinalizer;
    private final RetrievalProjectionDeadLetterFinalizer projectionFinalizer;
    private final TaskService taskService;
    private final Map<String, OutboxBusinessFinalizer> adapters = new HashMap<>();
    private final MeterRegistry meterRegistry;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;
    private final String instanceId = "outbox-finalizer-" + UUID.randomUUID();
    private final int batchSize;
    private final Duration lease;
    private final long maxBackoffSeconds;

    public OutboxFinalizationExecutor(
            OutboxFinalizationRepository finalizations,
            SourceParseFailureFinalizer parseFailureFinalizer,
            SourceRetrievalProjectionFinalizer projectionFinalizer,
            TaskService taskService,
            SourceTopicNames topicNames,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            ObjectProvider<OutboxBusinessFinalizer> registeredFinalizers,
            MeterRegistry meterRegistry,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            @Value("${noteweave.outbox.finalization.batch-size:20}") int batchSize,
            @Value("${noteweave.outbox.finalization.lease-seconds:120}") int leaseSeconds,
            @Value("${noteweave.outbox.finalization.max-backoff-seconds:300}") long maxBackoffSeconds
    ) {
        this.finalizations = finalizations;
        this.parseFailureFinalizer = parseFailureFinalizer;
        this.projectionFinalizer = new RetrievalProjectionDeadLetterFinalizer(
                objectMapper, projectionFinalizer, topicNames);
        this.taskService = taskService;
        registeredFinalizers.orderedStream().forEach(adapter -> {
            OutboxBusinessFinalizer previous = this.adapters.put(adapter.businessKind(), adapter);
            if (previous != null) {
                throw new IllegalStateException("重复注册的补偿 finalizer: kind=" + adapter.businessKind());
            }
        });
        this.meterRegistry = meterRegistry;
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = new TransactionTemplate(transactionManager);
        this.batchSize = Math.max(1, batchSize);
        this.lease = Duration.ofSeconds(Math.max(5, leaseSeconds));
        this.maxBackoffSeconds = Math.max(1, maxBackoffSeconds);
    }

    @Scheduled(fixedDelayString = "${noteweave.outbox.finalization.scan-delay-ms:5000}")
    public void scanDue() {
        List<OutboxFinalizationRepository.ClaimedFinalization> claimed =
                finalizations.claimDue(instanceId, batchSize, lease);
        for (OutboxFinalizationRepository.ClaimedFinalization work : claimed) {
            execute(work);
        }
    }

    /** 执行单条补偿并写回结果；异常或租约丢失都只影响本条。 */
    void execute(OutboxFinalizationRepository.ClaimedFinalization work) {
        FinalizationOutcome outcome;
        String errorCode = null;
        try {
            outcome = dispatch(work);
        } catch (BusinessException business) {
            outcome = FinalizationOutcome.RETRYABLE_FAILURE;
            errorCode = business.code();
        } catch (RuntimeException failure) {
            outcome = FinalizationOutcome.RETRYABLE_FAILURE;
            errorCode = failure.getClass().getSimpleName();
        }
        boolean committed;
        if (outcome == FinalizationOutcome.RETRYABLE_FAILURE) {
            long backoff = Math.min(maxBackoffSeconds, (1L << Math.min(10, work.attemptCount())) * 5L);
            committed = finalizations.retryLater(work.id(), work.claimToken(), errorCode,
                    "补偿收尾暂时失败: kind=" + work.businessKind(), backoff);
        } else if (outcome == FinalizationOutcome.TERMINAL_FAILURE) {
            // 明确终态（未知 kind、身份不可恢复）：不再退避重试，保持可查询与告警（FB-01 3.3）
            committed = finalizations.finish(work.id(), work.claimToken(),
                    OutboxFinalizationRepository.STATUS_TERMINAL_FAILURE, Instant.now());
        } else {
            committed = finalizations.finish(work.id(), work.claimToken(), statusOf(outcome), Instant.now());
        }
        if (!committed) {
            log.warn("outbox 补偿认领令牌已失效，结果不写回: id={}, kind={}, store={}, outboxId={}, generation={}",
                    work.id(), work.businessKind(), work.storeType(), work.outboxId(), work.deliveryGeneration());
            return;
        }
        meterRegistry.counter(
                "noteweave.outbox.finalization.outcome", "kind", metricKind(work.businessKind()),
                "outcome", outcome.name()
        ).increment();
        if (!outcome.success()) {
            log.warn("outbox 补偿收尾失败: id={}, kind={}, store={}, outboxId={}, generation={}, attempt={}/{}",
                    work.id(), work.businessKind(), work.storeType(), work.outboxId(),
                    work.deliveryGeneration(), work.attemptCount(), work.maxAttempts());
        }
    }

    private FinalizationOutcome dispatch(OutboxFinalizationRepository.ClaimedFinalization work) {
        Map<String, Object> identity = work.businessIdentity();
        if (Boolean.TRUE.equals(identity.get(OutboxFinalizationRepository.IDENTITY_UNRECOVERABLE_KEY))) {
            // 身份在登记时或读取时不可恢复：不能按空身份当陈旧任务，进入明确终态等待人工处置
            log.error("outbox 补偿身份不可恢复，进入终态: id={}, kind={}, store={}, outboxId={}, generation={}",
                    work.id(), work.businessKind(), work.storeType(), work.outboxId(), work.deliveryGeneration());
            return FinalizationOutcome.TERMINAL_FAILURE;
        }
        OutboxBusinessFinalizer adapter = adapters.get(work.businessKind());
        if (adapter != null) {
            // 注册式 finalizer（Research 等）携带自身代次围栏；再传 store 围栏供其复核
            return adapter.finalize(identity, fenceFor(work));
        }
        FinalizationOutcome dispatched;
        switch (work.businessKind()) {
            case KIND_SOURCE_PARSE -> dispatched = finalizeSourceParse(identity, fenceFor(work));
            case KIND_RETRIEVAL_PROJECTION -> dispatched = projectionFinalizer.finalizeFailureIdentity(
                    text(identity.get("topic")), identity,
                    textOrDefault(identity, "errorCode", RetrievalProjectionDeadLetterFinalizer.DELIVERY_EXHAUSTED_ERROR_CODE),
                    fenceFor(work));
            case KIND_KAFKA_DLT_PARSE -> dispatched = parseFailureFinalizer.finalizeProcessingFailed(
                    text(identity.get("taskId")), text(identity.get("workspaceId")),
                    text(identity.get("sourceId")), text(identity.get("snapshotId")),
                    textOrDefault(identity, "errorCode", "SOURCE_PARSE_FAILED"),
                    textOrDefault(identity, "errorMessage", "资料解析过程中出错"),
                    booleanOrDefault(identity, "retryable", true),
                    fenceFor(work));
            case KIND_KAFKA_DLT_PROJECTION -> dispatched = projectionFinalizer.finalizeFailureIdentity(
                    text(identity.get("topic")), identity,
                    textOrDefault(identity, "errorCode", "RETRIEVAL_PROJECTION_FAILED"),
                    fenceFor(work));
            case KIND_TASK_FALLBACK -> dispatched = finalizeTaskFallback(identity, fenceFor(work));
            default -> {
                // 未知 kind 不能 IGNORED_STALE 成功消失（FB-01 3.3）：明确终态 + 结构化告警
                log.error("outbox 补偿登记了未知业务类型，进入终态等待人工处置: id={}, kind={}, store={}, outboxId={}, generation={}",
                        work.id(), work.businessKind(), work.storeType(), work.outboxId(), work.deliveryGeneration());
                dispatched = FinalizationOutcome.TERMINAL_FAILURE;
            }
        }
        return dispatched;
    }

    /**
     * 按 store 构造业务代次围栏。TASK_OUTBOX 锁定并核对死言行；KAFKA_DLT 以
     * TASK_REDRIVEN 事件为当前业务阶段围栏；AGENT_OUTBOX 由注册 finalizer 自行核对。
     */
    private FinalizationFence fenceFor(OutboxFinalizationRepository.ClaimedFinalization work) {
        return switch (work.storeType()) {
            case OutboxFinalizationRepository.STORE_TASK -> () -> {
                // for update 与人工重驱的换代 CAS 串行化：围栏通过后重驱必须等本事务提交
                List<int[]> rows = jdbcTemplate.query("""
                        select delivery_generation, status from task_outbox where id = ? for update
                        """, (rs, rowNum) -> new int[]{rs.getInt("delivery_generation"),
                        "DEAD_LETTER".equals(rs.getString("status")) ? 1 : 0}, work.outboxId());
                return rows.size() == 1
                        && rows.get(0)[0] == work.deliveryGeneration()
                        && rows.get(0)[1] == 1;
            };
            case OutboxFinalizationRepository.STORE_KAFKA_DLT -> () -> {
                String taskId = text(work.businessIdentity().get("taskId"));
                if (taskId == null || taskId.isBlank()) {
                    // 无法定位任务的 DLT 意图没有可核对的业务阶段，按过期事实退出
                    return false;
                }
                Integer redriven = jdbcTemplate.queryForObject("""
                        select count(*) from task_event
                        where task_id = ? and event_type = 'TASK_REDRIVEN'
                          and created_at >= ?
                        """, Integer.class, taskId,
                        java.sql.Timestamp.from(work.registeredAt() == null
                                ? Instant.EPOCH : work.registeredAt()));
                return redriven != null && redriven == 0;
            };
            default -> () -> true;
        };
    }

    /** 解析/切片阶段投递耗尽：身份不匹配或任务终态按过期跳过，不重复失败事件。 */
    private FinalizationOutcome finalizeSourceParse(Map<String, Object> identity, FinalizationFence fence) {
        String taskId = text(identity.get("taskId"));
        if (taskId == null || taskId.isBlank()) {
            return FinalizationOutcome.IGNORED_STALE;
        }
        try {
            return parseFailureFinalizer.finalizeDeliveryExhausted(
                    taskId, text(identity.get("workspaceId")), text(identity.get("sourceId")),
                    text(identity.get("snapshotId")), fence);
        } catch (BusinessException mismatch) {
            // 身份不匹配说明死信指向的任务已不属于该资料，属于过期事实
            if (String.valueOf(mismatch.code()).startsWith("SOURCE_PARSE_OUTBOX_")) {
                return FinalizationOutcome.IGNORED_STALE;
            }
            throw mismatch;
        }
    }

    /** 无专属 finalizer 的任务兜底收尾：围栏与任务失败在同一短事务提交（FB-01）。 */
    private FinalizationOutcome finalizeTaskFallback(Map<String, Object> identity, FinalizationFence fence) {
        String taskId = text(identity.get("taskId"));
        if (taskId == null || taskId.isBlank()) {
            return FinalizationOutcome.IGNORED_STALE;
        }
        Boolean applied = transactions.execute(status -> {
            if (fence != null && !fence.stillCurrent()) {
                return Boolean.FALSE;
            }
            taskService.failTask(taskId, "OUTBOX_DEAD_LETTER", "Kafka Outbox 投递耗尽，已进入死信",
                    "OUTBOX_DISPATCH_EXHAUSTED", true);
            return Boolean.TRUE;
        });
        return Boolean.TRUE.equals(applied) ? FinalizationOutcome.APPLIED : FinalizationOutcome.IGNORED_STALE;
    }

    private String statusOf(FinalizationOutcome outcome) {
        return switch (outcome) {
            case APPLIED -> OutboxFinalizationRepository.STATUS_APPLIED;
            case IGNORED_STALE -> OutboxFinalizationRepository.STATUS_IGNORED_STALE;
            case TERMINAL_FAILURE -> OutboxFinalizationRepository.STATUS_TERMINAL_FAILURE;
            case RETRYABLE_FAILURE -> OutboxFinalizationRepository.STATUS_RETRY_WAIT;
        };
    }

    /** 指标 kind 标签有限枚举：未知类型统一 unknown，不用任意载荷造高基数（FB-09）。 */
    private String metricKind(String kind) {
        return KNOWN_KINDS.contains(kind) ? kind : "unknown";
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String textOrDefault(Map<String, Object> identity, String key, String fallback) {
        Object value = identity.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private boolean booleanOrDefault(Map<String, Object> identity, String key, boolean fallback) {
        Object value = identity.get(key);
        return value instanceof Boolean flag ? flag : fallback;
    }
}
