package com.noteweave.retrieval.projection;

import com.noteweave.knowledge.WikiIngestIntentOutcome;
import com.noteweave.knowledge.WikiReadyIngestIntentHandler;
import com.noteweave.knowledge.WikiReadySourceIntent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * source READY 摄取意图的唯一调度循环（FB-02 / C3，B 独占实现）。
 *
 * <p>READY 事务内由 {@link SourceReadyIntentRepository#recordIfAbsent} 持久化意图，
 * 本调度器认领租约后调用 A 的 {@link WikiReadyIngestIntentHandler} 建 Wiki 任务。
 * 认领短事务；业务调用在事务外编排；回写按一次性 claim_token CAS。
 *
 * <p>恢复语义（N-06）：认领不按 created_at 新鲜度过滤——服务停机任意时长后，
 * 未终态意图仍按顺序恢复。暂时失败退避重试，达上限 FAILED 终态（毒性意图不阻塞后续）；
 * 快照被取代、来源删除、业务不需要由处理结果 IGNORED_STALE 终态化并保留原因。
 *
 * <p>暂停语义（FB-02.6 + AU-003）：ready-dispatch-enabled=false 是「调度器暂停」——
 * 意图保持 PENDING 不被消费、不判陈旧，重新启用后从积压继续。业务功能开关
 * （A 侧 Wiki 关闭）由处理器返回 PARKED 表达：不终态化、不消耗重试预算，
 * 长延迟停放后重投，功能重新启用后自然恢复——暂停窗口的合法积压不丢失。
 * 与业务不需要（IGNORED_STALE 终态）不同。
 */
@Service
public class SourceReadyIntentDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SourceReadyIntentDispatcher.class);

    /** PARKED 停放时长（AU-003）：功能关闭期间每小时轻探一次，重新启用后下一轮即恢复。 */
    private static final long PARK_DELAY_SECONDS = 3600;

    private final SourceReadyIntentRepository intents;
    private final ObjectProvider<WikiReadyIngestIntentHandler> handler;
    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;
    private final String instanceId = "ready-intent-" + UUID.randomUUID();
    private final boolean enabled;
    private final int batchSize;
    private final Duration lease;
    private final long maxBackoffSeconds;
    private final int backfillPageSize;
    private final String ingestPolicyVersion;
    private final Map<String, java.util.concurrent.atomic.AtomicLong> metricHolders = new java.util.concurrent.ConcurrentHashMap<>();

    public SourceReadyIntentDispatcher(
            SourceReadyIntentRepository intents,
            ObjectProvider<WikiReadyIngestIntentHandler> handler,
            JdbcTemplate jdbcTemplate,
            MeterRegistry meterRegistry,
            @Value("${noteweave.knowledge.ready-dispatch-enabled:true}") boolean enabled,
            @Value("${noteweave.knowledge.ready-dispatch-batch-size:20}") int batchSize,
            @Value("${noteweave.knowledge.ready-dispatch-lease-seconds:120}") int leaseSeconds,
            @Value("${noteweave.knowledge.ready-dispatch-max-backoff-seconds:600}") long maxBackoffSeconds,
            @Value("${noteweave.knowledge.ready-backfill-page-size:200}") int backfillPageSize,
            @Value("${noteweave.knowledge.ingest-policy-version:1}") String ingestPolicyVersion
    ) {
        this.intents = intents;
        this.handler = handler;
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);
        this.lease = Duration.ofSeconds(Math.max(5, leaseSeconds));
        this.maxBackoffSeconds = Math.max(1, maxBackoffSeconds);
        this.backfillPageSize = Math.max(1, backfillPageSize);
        this.ingestPolicyVersion = ingestPolicyVersion == null || ingestPolicyVersion.isBlank()
                ? "1" : ingestPolicyVersion;
    }

    /**
     * 主循环：认领到期意图并调用 A 的处理器。
     * 处理器 bean 缺失按可重试失败退避（缺 C3 必要 bean 不能静默当成功，FB-09）。
     */
    @Scheduled(fixedDelayString = "${noteweave.knowledge.ready-dispatch-delay-ms:30000}")
    public void dispatchDue() {
        if (!enabled) {
            return;
        }
        List<SourceReadyIntentRepository.ReadySourceIntent> claimed =
                intents.claimDue(instanceId, batchSize, lease);
        for (SourceReadyIntentRepository.ReadySourceIntent intent : claimed) {
            dispatchOne(intent);
        }
        if (!claimed.isEmpty()) {
            publishMetrics();
        }
    }

    /**
     * 历史缺口回填：分页扫描 READY 资料中缺意图的行。单次调度最多推进一页，
     * 每页按快照 id 游标公平翻页；扫描过全量后自动停止（下一轮从空游标重新对账）。
     */
    @Scheduled(fixedDelayString = "${noteweave.knowledge.ready-backfill-delay-ms:300000}")
    public void backfillMissing() {
        if (!enabled) {
            return;
        }
        String cursor = "";
        for (int page = 0; page < 5; page++) {
            SourceReadyIntentRepository.BackfillPage result =
                    intents.backfillMissing(ingestPolicyVersion, backfillPageSize, cursor);
            if (result.recorded() > 0) {
                log.info("READY 意图回填一页: recorded={}, scanned={}, cursor={}",
                        result.recorded(), result.scanned(), result.lastSnapshotId());
            }
            if (result.scanned() < backfillPageSize) {
                return;
            }
            cursor = result.lastSnapshotId();
        }
    }

    private void dispatchOne(SourceReadyIntentRepository.ReadySourceIntent claimed) {
        WikiReadyIngestIntentHandler consumer = handler.getIfAvailable();
        if (consumer == null) {
            // C3 处理器（A 侧 bean）缺失：可观察退避，不静默丢弃也不无限忙转
            log.error("Wiki ready intent handler bean is unavailable: intentId={}", claimed.intentId());
            intents.retryWait(claimed.intentId(), claimed.claimToken(), "HANDLER_UNAVAILABLE", 60);
            return;
        }
        WikiIngestIntentOutcome outcome;
        try {
            outcome = consumer.handle(toContract(claimed));
        } catch (RuntimeException failure) {
            log.warn("Wiki ready intent dispatch failed: intentId={}, reason={}",
                    claimed.intentId(), failure.getMessage());
            outcome = WikiIngestIntentOutcome.RETRYABLE_FAILURE;
        }
        switch (outcome) {
            case APPLIED -> {
                intents.complete(claimed.intentId(), claimed.claimToken());
                meterRegistry.counter("noteweave.ready-intent.outcome", "outcome", "APPLIED").increment();
            }
            case IGNORED_STALE -> {
                intents.ignoreStale(claimed.intentId(), claimed.claimToken(), "STALE_BY_HANDLER");
                meterRegistry.counter("noteweave.ready-intent.outcome", "outcome", "IGNORED_STALE").increment();
            }
            case PARKED -> {
                // AU-003：功能关闭（如 Wiki 停用）——停放不终态化、不消耗重试预算
                intents.park(claimed.intentId(), claimed.claimToken(), "WIKI_DISABLED", PARK_DELAY_SECONDS);
                meterRegistry.counter("noteweave.ready-intent.outcome", "outcome", "PARKED").increment();
            }
            case RETRYABLE_FAILURE -> retryWithBackoff(claimed, "RETRYABLE_FAILURE");
        }
    }

    private void retryWithBackoff(SourceReadyIntentRepository.ReadySourceIntent claimed, String errorCode) {
        SourceReadyIntentRepository.IntentProgress progress = intents.progressOf(claimed.intentId());
        int attempts = progress == null ? 1 : progress.attempts();
        int maxAttempts = progress == null ? 5 : progress.maxAttempts();
        if (attempts >= maxAttempts) {
            // 毒性意图达上限：终态化并告警，不阻塞队列（FB-02 验收）
            log.error("Wiki ready intent exhausted retries: intentId={}, attempts={}, sourceId={}",
                    claimed.intentId(), attempts, claimed.sourceId());
            intents.failTerminal(claimed.intentId(), claimed.claimToken(), errorCode);
            meterRegistry.counter("noteweave.ready-intent.outcome", "outcome", "FAILED").increment();
            return;
        }
        long backoff = Math.min(maxBackoffSeconds, (1L << Math.min(6, attempts)) * 30L);
        intents.retryWait(claimed.intentId(), claimed.claimToken(), errorCode, backoff);
        meterRegistry.counter("noteweave.ready-intent.outcome", "outcome", "RETRY_WAIT").increment();
    }

    /** 组装 A 的消费契约 DTO：generation 取快照当前 version_no（权威读，不用登记时冻结值）。 */
    private WikiReadySourceIntent toContract(SourceReadyIntentRepository.ReadySourceIntent claimed) {
        Long versionNo = jdbcTemplate.query("""
                select version_no from source_snapshot where id = ?
                """, rs -> rs.next() ? rs.getLong(1) : null, claimed.snapshotId());
        return new WikiReadySourceIntent(
                claimed.intentId(),
                claimed.workspaceId(),
                claimed.sourceId(),
                claimed.snapshotId(),
                versionNo == null ? 0L : versionNo,
                claimed.ingestPolicyVersion());
    }

    private String ingestPolicyVersion() {
        return ingestPolicyVersion;
    }

    /** 指标：各状态计数与最老未决年龄，另含调度开关可见性（FB-02.9 / FB-09）。 */
    private void publishMetrics() {
        Map<String, Long> metrics = intents.metrics();
        metrics.forEach((key, value) -> metricHolders
                .computeIfAbsent(key.replace('.', '_'),
                        ignored -> {
                            java.util.concurrent.atomic.AtomicLong holder =
                                    new java.util.concurrent.atomic.AtomicLong();
                            meterRegistry.gauge("noteweave.ready.intent." + key.replace("ready-intent.", "")
                                    .replace('.', '_'), holder);
                            return holder;
                        })
                .set(value));
        meterRegistry.counter("noteweave.ready-intent.dispatch", "enabled", String.valueOf(enabled)).increment();
    }
}
