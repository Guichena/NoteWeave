package com.noteweave.memory;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 产物编辑事件的持久两阶段处理（B-08 / FB-04，M-ARTIFACT-EDIT）。
 *
 * <p>回执是唯一可信输入源（FB-04 6.2）：事件首次到达即把完整有界输入快照
 * （schemaVersion/payloadDigest/actingUserId/base+new version/before+after digest/
 * changedSections/diffStats/diffFragments）写入回执；之后的一切处理与恢复扫描都
 * 只读回执，不依赖内存消息。同 eventId 不同 payloadDigest 是明确冲突，不替换旧载荷。
 *
 * <p>两个阶段独立状态机（FB-04 6.1）：每个阶段
 * PENDING → RUNNING → APPLIED / SKIPPED 或 RETRY_WAIT / TERMINAL_FAILURE，
 * 独立 attempts / dueAt / errorCode，一个阶段的失败不影响另一阶段已完成的事实。
 * 业务效果与阶段完成状态在同一短事务提交；模型调用永远在事务外，
 * 完成写入前先按 claim_token 复核认领仍属于本次执行。
 *
 * <p>处理开关关闭时（artifact-edit-enabled=false），合法事件仍持久化 PENDING 回执
 * 后确认（FB-03 5.2）：传输事实保留，重新启用后由恢复扫描接管，不静默丢弃。
 */
@Service
public class MemoryArtifactEditService {

    public static final String OUTCOME_PENDING = "PENDING";
    public static final String OUTCOME_RUNNING = "RUNNING";
    public static final String OUTCOME_APPLIED = "APPLIED";
    public static final String OUTCOME_SKIPPED = "SKIPPED";
    public static final String OUTCOME_RETRY = "RETRY_WAIT";
    public static final String OUTCOME_TERMINAL = "TERMINAL_FAILURE";

    private static final Logger log = LoggerFactory.getLogger(MemoryArtifactEditService.class);
    /** 章节拼接的输入上限：与既有编辑距离的字符上限一致，长文尾部修改不因截断而丢失判定。 */
    static final int MAX_EVENT_CHARS = 4_000;
    static final String TARGET_TYPE_ARTIFACT_RUN = "ARTIFACT_JOB_RUN";
    /** 输入快照的有界上限：超限丢弃的只是补充字段，身份与差异事实仍完整保留。 */
    static final int MAX_SECTIONS = 64;
    static final int MAX_SECTION_CHARS = 200;
    static final int MAX_FRAGMENTS = 16;
    static final int MAX_FRAGMENT_CHARS = 500;
    private static final long STAGE_BACKOFF_SECONDS = 120;
    private static final long LEASE_SECONDS = 120;

    private final JdbcTemplate jdbcTemplate;
    private final MemoryApplicationFeedbackService applicationFeedback;
    private final MemoryConversationExtractionService extraction;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    private final String instanceId = "artifact-edit-" + UUID.randomUUID();

    public MemoryArtifactEditService(
            JdbcTemplate jdbcTemplate,
            MemoryApplicationFeedbackService applicationFeedback,
            MemoryConversationExtractionService extraction,
            PlatformTransactionManager transactionManager,
            @Value("${noteweave.memory.artifact-edit-enabled:false}") boolean enabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.applicationFeedback = applicationFeedback;
        this.extraction = extraction;
        this.transactions = new TransactionTemplate(transactionManager);
        this.enabled = enabled;
    }

    /**
     * C4 v2 编辑事件。changedSections 是章节标题/标识；偏好抽取与幅度计算必须使用
     * diffFragments（受限正文差异片段）与 diffStats（可信差异统计），不以标题冒充正文（N-04）。
     * actingUserId 为 A 从认证上下文生成的操作用户；缺失时候选阶段保守跳过，不伪造归属。
     */
    public record EditEvent(
            String editEventId,
            String workspaceId,
            String artifactVersionId,
            String baseVersionId,
            String originTaskId,
            String beforeDigest,
            String afterDigest,
            List<String> changedSections,
            String actingUserId,
            int schemaVersion,
            Map<String, Object> diffStats,
            List<String> diffFragments
    ) {
        /** 兼容 v1 九字段构造（旧测试与过渡期）：无差异统计与片段。 */
        public EditEvent(
                String editEventId, String workspaceId, String artifactVersionId, String baseVersionId,
                String originTaskId, String beforeDigest, String afterDigest,
                List<String> changedSections, String actingUserId) {
            this(editEventId, workspaceId, artifactVersionId, baseVersionId, originTaskId,
                    beforeDigest, afterDigest, changedSections, actingUserId, 1, null, null);
        }
    }

    /** 处理结果：两个阶段各自的状态与原因码。 */
    public record EditOutcome(String outcomeStatus, String candidateStatus, String reasonCode) { }

    /** 回执上持久化的有界输入快照：重放只依赖这里，不再依赖内存消息。 */
    record ReceiptInput(
            String editEventId, String workspaceId, String artifactVersionId, String originTaskId,
            String actingUserId, int schemaVersion, String payloadDigest,
            List<String> changedSections, Map<String, Object> diffStats, List<String> diffFragments
    ) { }

    /**
     * 幂等入口：先持久回执与输入快照，再按开关决定立即处理或暂停保留。
     * 重复事件返回既有回执状态；同 eventId 不同载荷抛 IDEMPOTENCY_CONFLICT，不替换旧载荷。
     */
    public EditOutcome handleArtifactEdit(EditEvent event) {
        if (event == null || blank(event.editEventId()) || blank(event.workspaceId())) {
            return new EditOutcome(OUTCOME_SKIPPED, OUTCOME_SKIPPED, "EVENT_DISABLED_OR_INVALID");
        }
        if (sameText(event.beforeDigest(), event.afterDigest())) {
            // 内容无变化不入队（C4 合同）：到达这里的视为无效事件，无副作用跳过
            return new EditOutcome(OUTCOME_SKIPPED, OUTCOME_SKIPPED, "NO_CONTENT_CHANGE");
        }
        transactions.executeWithoutResult(status -> ensureReceipt(event));
        if (!enabled) {
            // FB-03：处理关闭时仍保留可恢复 PENDING 回执再确认；重启开启后恢复扫描接管
            return new EditOutcome(OUTCOME_PENDING, OUTCOME_PENDING, "PROCESSING_PAUSED");
        }
        return processReceipt(event.workspaceId(), event.editEventId());
    }

    /**
     * 恢复扫描（FB-04 6.1）：认领 PENDING（含暂停后重新启用）、到期 RETRY_WAIT、
     * 租约过期的 RUNNING。两阶段可独立重试，达上限 TERMINAL_FAILURE。
     */
    /**
     * 积压指标（MEM-04）：开关关闭时合法事件只写 PENDING 回执、不再推进，
     * 没有指标就看不出堆积。按「任一阶段处于该状态」计数，并带上开关自身状态。
     */
    public Map<String, Long> receiptBacklog() {
        Map<String, Long> metrics = new java.util.LinkedHashMap<>();
        metrics.put("enabled", enabled ? 1L : 0L);
        metrics.put("pending", countReceiptsWhere("outcome_status = 'PENDING' or candidate_status = 'PENDING'"));
        metrics.put("retry_wait", countReceiptsWhere(
                "outcome_status = 'RETRY_WAIT' or candidate_status = 'RETRY_WAIT'"));
        metrics.put("terminal_failure", countReceiptsWhere(
                "outcome_status = 'TERMINAL_FAILURE' or candidate_status = 'TERMINAL_FAILURE'"));
        return metrics;
    }

    private long countReceiptsWhere(String condition) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from memory_artifact_edit_receipt where " + condition, Long.class);
        return count == null ? 0L : count;
    }

    @Scheduled(fixedDelayString = "${noteweave.memory.artifact-edit-scan-delay-ms:60000}")
    public void scanDue() {
        if (!enabled) {
            return;
        }
        List<Map<String, Object>> due = jdbcTemplate.queryForList("""
                select workspace_id, edit_event_id from memory_artifact_edit_receipt
                where (
                    (outcome_status in ('PENDING', 'RETRY_WAIT')
                        and (outcome_next_attempt_at is null or outcome_next_attempt_at <= current_timestamp))
                    or (outcome_status = 'RUNNING' and (lease_until is null or lease_until < current_timestamp))
                    or (candidate_status in ('PENDING', 'RETRY_WAIT')
                        and (candidate_next_attempt_at is null or candidate_next_attempt_at <= current_timestamp))
                    or (candidate_status = 'RUNNING' and (lease_until is null or lease_until < current_timestamp))
                )
                order by created_at, id limit 20
                """);
        for (Map<String, Object> row : due) {
            try {
                processReceipt(String.valueOf(row.get("workspace_id")), String.valueOf(row.get("edit_event_id")));
            } catch (RuntimeException failure) {
                log.warn("产物编辑回执恢复失败: editEventId={}, reason={}",
                        row.get("edit_event_id"), failure.getMessage());
            }
        }
    }

    /**
     * 首次到达的输入快照。截断只发生在补充字段（章节/片段/统计），身份与摘要完整保留。
     */
    private void ensureReceipt(EditEvent event) {
        List<String> sections = boundedSections(event.changedSections());
        List<String> fragments = boundedFragments(event.diffFragments());
        Map<String, Object> stats = boundedStats(event.diffStats());
        String digest = payloadDigest(event, sections, stats, fragments);
        try {
            jdbcTemplate.update("""
                    insert into memory_artifact_edit_receipt(
                        id, workspace_id, edit_event_id, artifact_version_id, origin_task_id, after_digest,
                        outcome_status, candidate_status, max_attempts,
                        schema_version, payload_digest, acting_user_id, base_version_id, before_digest,
                        changed_sections_json, diff_stats_json, diff_fragments_json,
                        created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, 'PENDING', 'PENDING', 3, ?, ?, ?, ?, ?, ?, ?, ?,
                        current_timestamp, current_timestamp)
                    """, Ids.newId(), event.workspaceId(), event.editEventId(), event.artifactVersionId(),
                    event.originTaskId(), event.afterDigest(),
                    Math.max(1, event.schemaVersion()), digest, event.actingUserId(), event.baseVersionId(),
                    event.beforeDigest(),
                    Json.write(new com.fasterxml.jackson.databind.ObjectMapper(), sections),
                    stats.isEmpty() ? null : Json.write(new com.fasterxml.jackson.databind.ObjectMapper(), stats),
                    fragments.isEmpty() ? null : Json.write(new com.fasterxml.jackson.databind.ObjectMapper(), fragments));
        } catch (DuplicateKeyException duplicate) {
            String existing = existingPayloadDigest(event.workspaceId(), event.editEventId());
            if (existing != null && !existing.equals(digest)) {
                // 同 eventId 不同载荷：明确冲突，保留既有回执与输入，不用新正文替换（FB-04 6.2.3）
                throw new BusinessException("IDEMPOTENCY_CONFLICT",
                        "同一编辑事件到达了不同载荷: editEventId=" + event.editEventId());
            }
            // 重复投递且载荷一致：按既有回执继续，输入以回执为准
        }
    }

    private String existingPayloadDigest(String workspaceId, String editEventId) {
        List<String> digests = jdbcTemplate.queryForList("""
                select payload_digest from memory_artifact_edit_receipt
                where workspace_id = ? and edit_event_id = ?
                """, String.class, workspaceId, editEventId);
        return digests.isEmpty() ? null : digests.get(0);
    }

    /** 认领并顺序执行两个阶段；每个阶段独立 CAS，认领令牌在行级租约上一次性生成。 */
    EditOutcome processReceipt(String workspaceId, String editEventId) {
        String claimToken = Ids.newId();
        int claimed = jdbcTemplate.update("""
                update memory_artifact_edit_receipt
                set lease_owner = ?, lease_until = timestampadd(second, ?, current_timestamp),
                    claim_token = ?, updated_at = current_timestamp
                where workspace_id = ? and edit_event_id = ?
                  and (lease_until is null or lease_until < current_timestamp)
                  and (
                    (outcome_status in ('PENDING', 'RETRY_WAIT')
                        and (outcome_next_attempt_at is null or outcome_next_attempt_at <= current_timestamp))
                    or outcome_status = 'RUNNING'
                    or (candidate_status in ('PENDING', 'RETRY_WAIT')
                        and (candidate_next_attempt_at is null or candidate_next_attempt_at <= current_timestamp))
                    or candidate_status = 'RUNNING'
                  )
                """, instanceId, LEASE_SECONDS, claimToken, workspaceId, editEventId);
        if (claimed != 1) {
            return currentOutcome(workspaceId, editEventId, "CLAIM_LOST");
        }
        ReceiptInput input = loadInput(workspaceId, editEventId);
        String outcomeStatus = runOutcomeStage(workspaceId, editEventId, claimToken, input);
        String candidateStatus = runCandidateStage(workspaceId, editEventId, claimToken, input);
        releaseLease(workspaceId, editEventId, claimToken);
        return new EditOutcome(outcomeStatus, candidateStatus, finalReason(outcomeStatus, candidateStatus));
    }

    /**
     * outcome 阶段：编辑幅度取可信差异统计，其次受限差异片段，最后才是章节标题密度；
     * 没有任何差异输入时按 0 处理（SKIPPED），绝不把空差异计成满幅 1.0（RB-006）。
     * 业务效果（usage 反馈）与阶段完成在同一事务提交。
     */
    private String runOutcomeStage(String workspaceId, String editEventId, String claimToken, ReceiptInput input) {
        int entered = jdbcTemplate.update("""
                update memory_artifact_edit_receipt
                set outcome_status = 'RUNNING', updated_at = current_timestamp
                where workspace_id = ? and edit_event_id = ? and claim_token = ?
                  and outcome_status in ('PENDING', 'RETRY_WAIT', 'RUNNING')
                """, workspaceId, editEventId, claimToken);
        if (entered != 1) {
            return stageStatus("outcome_status", workspaceId, editEventId);
        }
        try {
            if (input == null || !hasDiffInput(input)) {
                // 旧回执没有差异输入且不做正文重建：明确终态，不按空差异估算（FB-04 6.2.4）
                return completeStage("outcome", workspaceId, editEventId, claimToken,
                        OUTCOME_TERMINAL, "TERMINAL_INPUT_UNRECOVERABLE");
            }
            double magnitude = editMagnitude(input);
            if (magnitude <= 0) {
                return completeStage("outcome", workspaceId, editEventId, claimToken,
                        OUTCOME_SKIPPED, "NO_CONTENT_CHANGE");
            }
            if (blank(input.originTaskId())) {
                return completeStage("outcome", workspaceId, editEventId, claimToken,
                        OUTCOME_SKIPPED, "NO_ORIGIN_TASK");
            }
            // NR17：认领复核（行锁）+ 业务效果 + 阶段终态同一事务——此前效果先提交、
            // 终态后写，租约过期重投会重复放大编辑幅度
            return transactions.execute(status -> {
                requireClaim(workspaceId, editEventId, claimToken);
                Integer affected = applicationFeedback.recordArtifactEditMagnitude(
                        workspaceId, TARGET_TYPE_ARTIFACT_RUN, input.originTaskId(), magnitude);
                int rows = affected == null ? 0 : affected;
                String terminal = rows > 0 ? OUTCOME_APPLIED : OUTCOME_SKIPPED;
                writeStageTerminal("outcome", workspaceId, editEventId, claimToken,
                        terminal, rows > 0 ? null : "NO_USAGE");
                return terminal;
            });
        } catch (RuntimeException failure) {
            log.warn("产物编辑 outcome 阶段失败: editEventId={}, reason={}", editEventId, failure.getMessage());
            return retryStage("outcome", workspaceId, editEventId, claimToken, "STAGE_RETRYABLE");
        }
    }

    /**
     * candidate 阶段：新偏好必须经过模型判定 + signal→candidate→门控；
     * 模型调用在事务外，完成写入前先按 claim_token 复核；模型暂不可用 RETRY_WAIT，
     * 合法空偏好 SKIPPED/NO_PREFERENCE，无操作用户不伪造候选。
     */
    private String runCandidateStage(String workspaceId, String editEventId, String claimToken, ReceiptInput input) {
        int entered = jdbcTemplate.update("""
                update memory_artifact_edit_receipt
                set candidate_status = 'RUNNING', updated_at = current_timestamp
                where workspace_id = ? and edit_event_id = ? and claim_token = ?
                  and candidate_status in ('PENDING', 'RETRY_WAIT', 'RUNNING')
                """, workspaceId, editEventId, claimToken);
        if (entered != 1) {
            return stageStatus("candidate_status", workspaceId, editEventId);
        }
        try {
            if (input == null || !hasDiffInput(input)) {
                return completeStage("candidate", workspaceId, editEventId, claimToken,
                        OUTCOME_SKIPPED, "INPUT_UNAVAILABLE");
            }
            if (blank(input.actingUserId())) {
                // 候选归属操作用户：事件未携带时不伪造，等待 A 的 C4 提供该字段
                return completeStage("candidate", workspaceId, editEventId, claimToken,
                        OUTCOME_SKIPPED, "NO_ACTOR");
            }
            String modelInput = fragmentsText(input.diffFragments());
            if (modelInput.isBlank()) {
                modelInput = sectionsText(input.changedSections());
            }
            if (modelInput.isBlank()) {
                return completeStage("candidate", workspaceId, editEventId, claimToken,
                        OUTCOME_SKIPPED, "NO_CONTENT_CHANGE");
            }
            MemoryConversationExtractionService.Extraction result =
                    extraction.extractPreferences(modelInput, "");
            if (result.retryable()) {
                // 模型暂时不可用是可重试故障，不能一概 SKIPPED 丢掉（FB-04 6.1）
                return retryStage("candidate", workspaceId, editEventId, claimToken, "MODEL_RETRYABLE");
            }
            if (result.preferences().isEmpty()) {
                return completeStage("candidate", workspaceId, editEventId, claimToken,
                        OUTCOME_SKIPPED, "NO_PREFERENCE");
            }
            // NR17：认领复核（行锁）+ 候选落地 + 阶段终态同一事务——此前候选先提交、
            // 终态后写，重投会经无唯一约束的 signal 重复造候选
            return transactions.execute(status -> {
                requireClaim(workspaceId, editEventId, claimToken);
                Integer created = extraction.persistArtifactEditCandidates(
                        workspaceId, input.actingUserId(), input.editEventId(), result);
                int rows = created == null ? 0 : created;
                String terminal = rows > 0 ? OUTCOME_APPLIED : OUTCOME_SKIPPED;
                writeStageTerminal("candidate", workspaceId, editEventId, claimToken,
                        terminal, rows > 0 ? null : "NO_PREFERENCE");
                return terminal;
            });
        } catch (RuntimeException failure) {
            log.warn("产物编辑 candidate 阶段失败: editEventId={}, reason={}", editEventId, failure.getMessage());
            return retryStage("candidate", workspaceId, editEventId, claimToken, "STAGE_RETRYABLE");
        }
    }

    /** 模型判定完成、准备落地候选前复核认领仍在手上；丢失则中止本阶段（调用方按可重试处理）。 */
    private void requireClaim(String workspaceId, String editEventId, String claimToken) {
        // NR17：按主键行 FOR UPDATE 锁定回执（聚合查询不允许 FOR UPDATE），
        // 复核 claim_token 与租约后才写业务效果与阶段终态
        List<String> held = jdbcTemplate.queryForList("""
                select id from memory_artifact_edit_receipt
                where workspace_id = ? and edit_event_id = ? and claim_token = ?
                  and lease_until >= current_timestamp
                for update
                """, String.class, workspaceId, editEventId, claimToken);
        if (held.isEmpty()) {
            throw new BusinessException("MEMORY_EDIT_CLAIM_LOST", "编辑回执认领已过期，本阶段中止");
        }
    }

    /** 阶段成功/终态写入：清空到期位、保留原因码；仅当仍持有 claim_token 时生效。 */
    private String completeStage(String stage, String workspaceId, String editEventId,
            String claimToken, String status, String reasonCode) {
        String column = stage + "_status";
        jdbcTemplate.update("""
                update memory_artifact_edit_receipt
                set %s = ?, %s_error_code = ?, %s_next_attempt_at = null, updated_at = current_timestamp
                where workspace_id = ? and edit_event_id = ? and claim_token = ?
                """.formatted(column, stage, stage), status, reasonCode, workspaceId, editEventId, claimToken);
        return status;
    }

    /** NR17：事务内的阶段终态写入（与业务效果同事务提交；供 lambda 内使用）。 */
    private void writeStageTerminal(String stage, String workspaceId, String editEventId,
            String claimToken, String status, String reasonCode) {
        String column = stage + "_status";
        jdbcTemplate.update("""
                update memory_artifact_edit_receipt
                set %s = ?, %s_error_code = ?, %s_next_attempt_at = null, updated_at = current_timestamp
                where workspace_id = ? and edit_event_id = ? and claim_token = ?
                """.formatted(column, stage, stage), status, reasonCode, workspaceId, editEventId, claimToken);
    }

    /** 阶段可重试失败：次数+1、退避到期；达共享上限进入 TERMINAL_FAILURE。 */
    private String retryStage(String stage, String workspaceId, String editEventId,
            String claimToken, String errorCode) {
        String attemptsColumn = stage + "_attempts";
        Integer attempts = jdbcTemplate.queryForObject("""
                select %s from memory_artifact_edit_receipt where workspace_id = ? and edit_event_id = ?
                """.formatted(attemptsColumn), Integer.class, workspaceId, editEventId);
        int next = (attempts == null ? 0 : attempts) + 1;
        Integer maxAttempts = jdbcTemplate.queryForObject("""
                select max_attempts from memory_artifact_edit_receipt
                where workspace_id = ? and edit_event_id = ?
                """, Integer.class, workspaceId, editEventId);
        int limit = maxAttempts == null ? 3 : maxAttempts;
        boolean terminal = next >= limit;
        // 上限来自回执的同一 max_attempts 字段，两个阶段共用，不再各算各的（FB-04 6.1）
        jdbcTemplate.update("""
                update memory_artifact_edit_receipt
                set %s = ?, %s = ?, %s_error_code = ?,
                    %s_next_attempt_at = case when ? then null
                        else timestampadd(second, ?, current_timestamp) end,
                    updated_at = current_timestamp
                where workspace_id = ? and edit_event_id = ? and claim_token = ?
                """.formatted(attemptsColumn, stage + "_status", stage, stage),
                next, terminal ? OUTCOME_TERMINAL : OUTCOME_RETRY, errorCode, terminal,
                STAGE_BACKOFF_SECONDS, workspaceId, editEventId, claimToken);
        return terminal ? OUTCOME_TERMINAL : OUTCOME_RETRY;
    }

    private void releaseLease(String workspaceId, String editEventId, String claimToken) {
        jdbcTemplate.update("""
                update memory_artifact_edit_receipt
                set lease_owner = null, lease_until = null, claim_token = null, updated_at = current_timestamp
                where workspace_id = ? and edit_event_id = ? and claim_token = ?
                """, workspaceId, editEventId, claimToken);
    }

    private ReceiptInput loadInput(String workspaceId, String editEventId) {
        return jdbcTemplate.query("""
                select edit_event_id, workspace_id, artifact_version_id, origin_task_id, acting_user_id,
                       schema_version, payload_digest, changed_sections_json, diff_stats_json, diff_fragments_json
                from memory_artifact_edit_receipt where workspace_id = ? and edit_event_id = ?
                """, rs -> rs.next() ? new ReceiptInput(
                rs.getString("edit_event_id"), rs.getString("workspace_id"),
                rs.getString("artifact_version_id"), rs.getString("origin_task_id"),
                rs.getString("acting_user_id"), rs.getInt("schema_version"), rs.getString("payload_digest"),
                readStringList(rs.getString("changed_sections_json")),
                readMap(rs.getString("diff_stats_json")),
                readStringList(rs.getString("diff_fragments_json"))) : null, workspaceId, editEventId);
    }

    private EditOutcome currentOutcome(String workspaceId, String editEventId, String defaultReason) {
        return new EditOutcome(
                stageStatus("outcome_status", workspaceId, editEventId),
                stageStatus("candidate_status", workspaceId, editEventId),
                defaultReason);
    }

    private String stageStatus(String column, String workspaceId, String editEventId) {
        List<String> values = jdbcTemplate.queryForList("""
                select %s from memory_artifact_edit_receipt where workspace_id = ? and edit_event_id = ?
                """.formatted(column), String.class, workspaceId, editEventId);
        return values.isEmpty() ? OUTCOME_PENDING : values.get(0);
    }

    private String finalReason(String outcomeStatus, String candidateStatus) {
        if (OUTCOME_TERMINAL.equals(outcomeStatus) || OUTCOME_TERMINAL.equals(candidateStatus)) {
            return "TERMINAL_FAILURE";
        }
        if (OUTCOME_RETRY.equals(outcomeStatus) || OUTCOME_RETRY.equals(candidateStatus)) {
            return "RETRY_WAIT";
        }
        if (OUTCOME_RUNNING.equals(outcomeStatus) || OUTCOME_RUNNING.equals(candidateStatus)) {
            return "IN_PROGRESS";
        }
        return "COMPLETED";
    }

    /** 差异输入可用性：统计、片段、章节三者至少一项非空才可估幅与抽取。 */
    static boolean hasDiffInput(ReceiptInput input) {
        return (input.diffStats() != null && !input.diffStats().isEmpty())
                || (input.diffFragments() != null && !input.diffFragments().isEmpty())
                || (input.changedSections() != null && !input.changedSections().isEmpty());
    }

    /**
     * 编辑幅度（FB-04 6.2.5）：优先可信差异统计（变更字符占全文比例），
     * 其次受限差异片段体量，最后章节标题密度下限；无输入为 0，不为满幅。
     */
    static double editMagnitude(ReceiptInput input) {
        Double fromStats = magnitudeFromStats(input.diffStats());
        if (fromStats != null) {
            return fromStats;
        }
        if (input.diffFragments() != null && !input.diffFragments().isEmpty()) {
            String joined = fragmentsText(input.diffFragments());
            String collapsed = joined.isBlank() ? "" : joined.replaceAll("\\s+", " ").trim();
            if (collapsed.isEmpty()) {
                return 0.0;
            }
            double density = Math.min(1.0, (double) collapsed.length() / MAX_EVENT_CHARS);
            return Math.max(0.05, Math.round(density * 100.0) / 100.0);
        }
        return magnitudeFromSections(input.changedSections());
    }

    /** 可信差异统计：changedChars / totalChars（A 的 diffStats 字段），有下界防长尾丢失。 */
    static Double magnitudeFromStats(Map<String, Object> diffStats) {
        if (diffStats == null || diffStats.isEmpty()) {
            return null;
        }
        Long changed = asLong(diffStats.get("changedChars"));
        Long total = asLong(diffStats.get("totalChars"));
        if (changed == null || changed <= 0 || total == null || total <= 0) {
            return null;
        }
        double ratio = Math.min(1.0, (double) changed / total);
        return Math.max(0.05, Math.round(ratio * 100.0) / 100.0);
    }

    /** 章节标题只决定下界密度，不再决定整体幅度：标题不变大改正文也能被统计/片段捕获。 */
    static double magnitudeFromSections(List<String> changedSections) {
        if (changedSections == null || changedSections.isEmpty()) {
            // 空差异不是满幅修改（RB-006）：没有任何差异输入时按 0 处理
            return 0.0;
        }
        String joined = sectionsText(changedSections);
        String collapsed = joined.isBlank() ? "" : joined.replaceAll("\\s+", " ").trim();
        if (collapsed.isEmpty()) {
            return 0.0;
        }
        double density = Math.min(1.0, (double) collapsed.length() / MAX_EVENT_CHARS);
        return Math.max(0.05, Math.round(density * 100.0) / 100.0);
    }

    static String sectionsText(List<String> changedSections) {
        return boundedJoin(changedSections, MAX_EVENT_CHARS);
    }

    static String fragmentsText(List<String> fragments) {
        return boundedJoin(fragments, MAX_EVENT_CHARS);
    }

    private static String boundedJoin(List<String> parts, int maxChars) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (text.length() >= maxChars) {
                break;
            }
            int room = maxChars - text.length() - 1;
            if (room <= 0) {
                break;
            }
            text.append(part, 0, Math.min(part.length(), room)).append('\n');
        }
        return text.toString();
    }

    static List<String> boundedSections(List<String> sections) {
        if (sections == null) {
            return List.of();
        }
        List<String> bounded = new ArrayList<>();
        for (String section : sections) {
            if (section == null || section.isBlank() || bounded.size() >= MAX_SECTIONS) {
                continue;
            }
            bounded.add(section.length() <= MAX_SECTION_CHARS ? section : section.substring(0, MAX_SECTION_CHARS));
        }
        return List.copyOf(bounded);
    }

    static List<String> boundedFragments(List<String> fragments) {
        if (fragments == null) {
            return List.of();
        }
        List<String> bounded = new ArrayList<>();
        for (String fragment : fragments) {
            if (fragment == null || fragment.isBlank() || bounded.size() >= MAX_FRAGMENTS) {
                continue;
            }
            bounded.add(fragment.length() <= MAX_FRAGMENT_CHARS ? fragment : fragment.substring(0, MAX_FRAGMENT_CHARS));
        }
        return List.copyOf(bounded);
    }

    static Map<String, Object> boundedStats(Map<String, Object> stats) {
        if (stats == null || stats.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> bounded = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : stats.entrySet()) {
            if (entry.getValue() instanceof Number || entry.getValue() instanceof Boolean
                    || entry.getValue() instanceof String) {
                bounded.put(entry.getKey(), entry.getValue());
            }
        }
        return bounded;
    }

    /** 载荷摘要：对身份与全部有界输入的规范化 JSON 计算，重复事件冲突判定依据。 */
    static String payloadDigest(EditEvent event, List<String> sections, Map<String, Object> stats,
            List<String> fragments) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("editEventId", event.editEventId());
        canonical.put("workspaceId", event.workspaceId());
        canonical.put("artifactVersionId", event.artifactVersionId());
        canonical.put("baseVersionId", event.baseVersionId());
        canonical.put("originTaskId", event.originTaskId());
        canonical.put("beforeDigest", event.beforeDigest());
        canonical.put("afterDigest", event.afterDigest());
        canonical.put("actingUserId", event.actingUserId());
        canonical.put("schemaVersion", Math.max(1, event.schemaVersion()));
        canonical.put("changedSections", sections);
        canonical.put("diffStats", stats);
        canonical.put("diffFragments", fragments);
        return sha256(Json.write(new com.fasterxml.jackson.databind.ObjectMapper(), canonical));
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && text.matches("\\d+")) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            if (node == null || !node.isArray()) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            node.forEach(item -> {
                if (item.isTextual() && !item.asText().isBlank()) {
                    values.add(item.asText());
                }
            });
            return values;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    json, new com.fasterxml.jackson.core.type.TypeReference<HashMap<String, Object>>() { });
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean sameText(String left, String right) {
        return left != null && left.equals(right);
    }
}
