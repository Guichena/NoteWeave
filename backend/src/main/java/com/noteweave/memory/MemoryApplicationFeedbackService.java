package com.noteweave.memory;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把回答反馈、重新生成、下一轮追问和产物编辑写到实际注入的记忆上。
 * 同一回答的点赞点踩以最后一次为准。原有的一次性 outcome 接口不走这里。
 */
@Service
public class MemoryApplicationFeedbackService {

    public static final String DISABLED = "MEMORY_OUTCOME_FEEDBACK_DISABLED";
    public static final String NO_APPLICATION = "MEMORY_FEEDBACK_NO_APPLICATION";
    public static final String SLOT_UNMATCHED = "MEMORY_FEEDBACK_SLOT_UNMATCHED";
    public static final String UTILITY_ADJUSTED = "MEMORY_FEEDBACK_UTILITY_ADJUSTED";

    static final String SIGNAL_RATING = "RATING";
    static final String SIGNAL_RETRY = "RETRY";
    static final String SIGNAL_EDIT = "EDIT";
    static final String SIGNAL_FOLLOW_UP = "FOLLOW_UP";
    static final String TARGET_MESSAGE = "CONVERSATION_MESSAGE";

    /**
     * 跟进反馈的处理结果：写接受、写负面，或这一轮不写。
     * {@link #RETRYABLE} 表示模型没给出可用结论，本轮跳过并沿用记忆抽取的可重试标记。
     */
    public enum FollowUpResult {
        ACCEPTED,
        NEGATIVE,
        SKIPPED,
        RETRYABLE
    }

    private final JdbcTemplate jdbcTemplate;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final MemoryOutcomePolicy outcomePolicy;
    private final MemoryFeedbackAspectJudge aspectJudge;
    private final boolean enabled;

    public MemoryApplicationFeedbackService(
            JdbcTemplate jdbcTemplate,
            WorkspaceAccessGuard workspaceAccessGuard,
            MemoryOutcomePolicy outcomePolicy,
            MemoryFeedbackAspectJudge aspectJudge,
            @Value("${noteweave.memory.outcome-feedback-enabled:true}") boolean enabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.outcomePolicy = outcomePolicy;
        this.aspectJudge = aspectJudge;
        this.enabled = enabled;
    }

    @Transactional
    public AnswerFeedbackResponse recordAnswerFeedback(
            String workspaceId,
            String runId,
            String rating,
            String reason
    ) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.ANSWER_RUN);
        String outcomeType = normalizeRating(rating);
        String answerMessageId = requireAnswerMessageId(workspaceId, runId);
        if (!enabled) {
            return new AnswerFeedbackResponse(runId, outcomeType, null, DISABLED, 0);
        }
        if (answerMessageId == null) {
            return new AnswerFeedbackResponse(runId, outcomeType, null, NO_APPLICATION, 0);
        }
        MemoryFeedbackAspectJudge.Judgment judgment = aspectJudge.judge(reason);
        List<UsageRow> usages = loadUsages(workspaceId, TARGET_MESSAGE, answerMessageId);
        List<UsageRow> selected = select(usages, outcomeType, judgment.aspect());
        String degradation = degradation(usages, selected, outcomeType, judgment);
        List<EventRow> active = loadActive(workspaceId, TARGET_MESSAGE, answerMessageId, SIGNAL_RATING);
        if (sameRating(active, selected, outcomeType, reason, judgment.aspect())) {
            return new AnswerFeedbackResponse(
                    runId,
                    outcomeType,
                    judgment.aspect(),
                    firstDegradation(active),
                    countItems(active)
            );
        }
        boolean approximate = reverse(active);
        if (approximate && degradation == null) {
            degradation = UTILITY_ADJUSTED;
        }
        int affected = apply(
                workspaceId,
                TARGET_MESSAGE,
                answerMessageId,
                SIGNAL_RATING,
                selected,
                outcomeType,
                null,
                updateWeight(outcomeType, judgment.aspect()),
                judgment.aspect(),
                reason,
                degradation,
                "rating:" + runId,
                betaFor(outcomeType, judgment.aspect())
        );
        if (selected.isEmpty()) {
            insertAnswerLevel(
                    workspaceId,
                    TARGET_MESSAGE,
                    answerMessageId,
                    SIGNAL_RATING,
                    outcomeType,
                    judgment.aspect(),
                    reason,
                    degradation,
                    "rating:" + runId
            );
        }
        return new AnswerFeedbackResponse(runId, outcomeType, judgment.aspect(), degradation, affected);
    }

    /**
     * 重新生成单独提交，失败时只回滚这一笔反馈，不回滚正在发送的新回合。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRegeneratedFrom(String workspaceId, String runId) {
        if (!enabled || runId == null || runId.isBlank()) {
            return;
        }
        String sourceRef = "retry:" + runId.trim();
        if (sourceExists(workspaceId, sourceRef)) {
            return;
        }
        String answerMessageId = optionalAnswerMessageId(workspaceId, runId.trim());
        if (answerMessageId == null) {
            return;
        }
        List<UsageRow> usages = loadUsages(workspaceId, TARGET_MESSAGE, answerMessageId);
        if (usages.isEmpty()) {
            return;
        }
        apply(workspaceId, TARGET_MESSAGE, answerMessageId, SIGNAL_RETRY, usages, "RETRIED",
                null, 1.0, null, null, null, sourceRef, false);
    }

    /**
     * 下一轮消息算不算跟进反馈、正/负/中性、针对哪个方面，全部由模型给出（见
     * {@link MemoryFeedbackAspectJudge}），这里只按模型结论写接受、写负面，或这一轮不写。
     * 模型没给出可用结论时本轮跳过，由调用方把任务标成可重试。
     */
    @Transactional
    public FollowUpResult recordFollowUp(String workspaceId, String userMessageId, String userContent) {
        if (!enabled || userMessageId == null || userMessageId.isBlank()) {
            return FollowUpResult.SKIPPED;
        }
        String sourceRef = "follow-up:" + userMessageId;
        if (sourceExists(workspaceId, sourceRef)) {
            return FollowUpResult.SKIPPED;
        }
        String assistantMessageId = previousAssistantId(workspaceId, userMessageId);
        if (assistantMessageId == null) {
            return FollowUpResult.SKIPPED;
        }
        List<UsageRow> usages = loadUsages(workspaceId, TARGET_MESSAGE, assistantMessageId);
        if (usages.isEmpty()) {
            return FollowUpResult.SKIPPED;
        }
        // 模型没配置时判断永远做不了，属于常态：跳过这一轮，不标可重试
        if (!aspectJudge.available()) {
            return FollowUpResult.SKIPPED;
        }
        MemoryFeedbackAspectJudge.Judgment judgment = aspectJudge.judge(userContent);
        FollowUpResult result = followUpResult(judgment);
        if (result == FollowUpResult.SKIPPED || result == FollowUpResult.RETRYABLE) {
            return result;
        }
        if (result == FollowUpResult.ACCEPTED) {
            List<UsageRow> eligible = usages.stream().filter(row -> !alreadySignaled(row)).toList();
            if (eligible.isEmpty()) {
                return FollowUpResult.SKIPPED;
            }
            apply(workspaceId, TARGET_MESSAGE, assistantMessageId, SIGNAL_FOLLOW_UP, eligible, "ACCEPTED",
                    null, 0.5, null, null, null, sourceRef, false);
            return FollowUpResult.ACCEPTED;
        }
        List<UsageRow> selected = select(usages, "NEGATIVE", judgment.aspect());
        String degradation = degradation(usages, selected, "NEGATIVE", judgment);
        if (selected.isEmpty()) {
            insertAnswerLevel(workspaceId, TARGET_MESSAGE, assistantMessageId, SIGNAL_FOLLOW_UP, "NEGATIVE",
                    judgment.aspect(), trimNote(userContent), degradation, sourceRef);
            return FollowUpResult.NEGATIVE;
        }
        apply(workspaceId, TARGET_MESSAGE, assistantMessageId, SIGNAL_FOLLOW_UP, selected, "NEGATIVE",
                null, updateWeight("NEGATIVE", judgment.aspect()), judgment.aspect(), trimNote(userContent),
                degradation, sourceRef, betaFor("NEGATIVE", judgment.aspect()));
        return FollowUpResult.NEGATIVE;
    }

    /**
     * 代码只按模型结论分支：模型没给出结论→本轮跳过并标可重试；
     * 不是对上一条回答的反馈或中性→不写；认可→写接受，不满→写负面。
     */
    static FollowUpResult followUpResult(MemoryFeedbackAspectJudge.Judgment judgment) {
        if (judgment.notJudged()) {
            return FollowUpResult.RETRYABLE;
        }
        if (!judgment.feedback()) {
            return FollowUpResult.SKIPPED;
        }
        return switch (judgment.polarity()) {
            case MemoryFeedbackAspectJudge.POLARITY_POSITIVE -> FollowUpResult.ACCEPTED;
            case MemoryFeedbackAspectJudge.POLARITY_NEGATIVE -> FollowUpResult.NEGATIVE;
            default -> FollowUpResult.SKIPPED;
        };
    }

    /**
     * 产物保存时由调用方传入修改前后的正文。
     * 目标应对这次生成写入 usage 时使用的 target，例如 ARTIFACT_JOB_RUN 的 task id。
     * 比例为 0 或没有注入记录时不写库。
     */
    @Transactional
    public int recordArtifactEdit(
            String workspaceId,
            String targetType,
            String targetId,
            String before,
            String after
    ) {
        if (!enabled || targetType == null || targetType.isBlank() || targetId == null || targetId.isBlank()) {
            return 0;
        }
        double magnitude = MemoryEditRatio.ratio(before, after);
        if (magnitude <= 0) {
            return 0;
        }
        String normalizedType = targetType.trim();
        String normalizedId = targetId.trim();
        List<UsageRow> usages = loadUsages(workspaceId, normalizedType, normalizedId);
        if (usages.isEmpty()) {
            return 0;
        }
        List<EventRow> active = loadActive(workspaceId, normalizedType, normalizedId, SIGNAL_EDIT);
        if (sameMagnitude(active, usages, magnitude)) {
            return countItems(active);
        }
        reverse(active);
        return apply(workspaceId, normalizedType, normalizedId, SIGNAL_EDIT, usages, "EDITED",
                magnitude, 1.0, null, null, null, "edit:" + normalizedType + ":" + normalizedId, false);
    }

    private int apply(
            String workspaceId,
            String targetType,
            String targetId,
            String signalKind,
            List<UsageRow> selected,
            String outcomeType,
            Double editMagnitude,
            double updateWeight,
            String aspect,
            String note,
            String degradation,
            String sourceRef,
            boolean addBeta
    ) {
        int affected = 0;
        boolean unknown = "UNKNOWN".equals(aspect);
        for (UsageRow usage : selected) {
            ItemState state = lockItem(workspaceId, usage.memoryItemId());
            double betaWeight = addBeta ? betaWeight(state.memoryItemId(), unknown) : 0;
            MemoryOutcomePolicy.Decision decision = outcomePolicy.evaluate(
                    state.toPolicyState(), outcomeType, editMagnitude, updateWeight);
            long rowNo = nextRowNo(usage.memoryItemId());
            insertEvent(workspaceId, usage, targetType, targetId, signalKind, decision, updateWeight, betaWeight,
                    aspect, note, degradation, sourceRef, editMagnitude, state, rowNo);
            writeItem(workspaceId, state, decision);
            writeUsage(workspaceId, usage.usageId(), decision, editMagnitude, note);
            if (addBeta && betaWeight > 0) {
                addBeta(state.memoryItemId(), betaWeight);
            }
            affected++;
        }
        return affected;
    }

    private boolean reverse(List<EventRow> active) {
        List<EventRow> ordered = new ArrayList<>(active);
        ordered.sort(Comparator.comparingLong(EventRow::rowNo).reversed());
        boolean approximate = false;
        for (EventRow row : ordered) {
            supersede(row.id());
            if (row.memoryItemId() == null || (row.applicationDelta() == 0 && row.utilityDelta() == 0)) {
                continue;
            }
            ItemState state = lockItem(row.workspaceId(), row.memoryItemId());
            boolean latest = laterUtilityCount(row) == 0;
            if (latest) {
                restore(state, row);
            } else {
                adjust(state, row);
                approximate = true;
            }
            if (row.betaWeight() > 0) {
                subtractBeta(row.memoryItemId(), row.betaWeight());
            }
            clearUsage(row);
        }
        return approximate;
    }

    private void insertAnswerLevel(
            String workspaceId,
            String targetType,
            String targetId,
            String signalKind,
            String outcomeType,
            String aspect,
            String note,
            String degradation,
            String sourceRef
    ) {
        jdbcTemplate.update("""
                insert into memory_application_outcome(
                    id, workspace_id, target_type, target_id, signal_kind, outcome_type,
                    outcome_score, weight, beta_weight, aspect, feedback_note, degradation_reason,
                    source_ref, row_no
                ) values (?, ?, ?, ?, ?, ?, 0, 0, 0, ?, ?, ?, ?, ?)
                """,
                Ids.newId(), workspaceId, targetType, targetId, signalKind, outcomeType,
                aspect, note, degradation, sourceRef, nextTargetRowNo(workspaceId, targetType, targetId, signalKind));
    }

    private void insertEvent(
            String workspaceId,
            UsageRow usage,
            String targetType,
            String targetId,
            String signalKind,
            MemoryOutcomePolicy.Decision decision,
            double updateWeight,
            double betaWeight,
            String aspect,
            String note,
            String degradation,
            String sourceRef,
            Double editMagnitude,
            ItemState before,
            long rowNo
    ) {
        double utilityDelta = round4(decision.utilityScore() - before.utilityScore());
        jdbcTemplate.update("""
                insert into memory_application_outcome(
                    id, workspace_id, usage_log_id, memory_item_id, memory_revision_id,
                    target_type, target_id, signal_kind, outcome_type, outcome_score, weight,
                    beta_weight, aspect, feedback_note, degradation_reason, source_ref, edit_magnitude,
                    utility_before, utility_delta, application_count_before, application_delta,
                    positive_count_before, positive_delta, negative_count_before, negative_delta,
                    edit_count_before, edit_delta, retry_count_before, retry_delta,
                    status_before, review_before, row_no
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                Ids.newId(),
                workspaceId,
                usage.usageId(),
                usage.memoryItemId(),
                usage.memoryRevisionId(),
                targetType,
                targetId,
                signalKind,
                decision.outcomeType(),
                decision.outcomeScore(),
                round4(updateWeight),
                round4(betaWeight),
                aspect,
                note,
                degradation,
                sourceRef,
                editMagnitude,
                before.utilityScore(),
                utilityDelta,
                before.applicationCount(),
                decision.applicationCount() - before.applicationCount(),
                before.positiveOutcomeCount(),
                decision.positiveOutcomeCount() - before.positiveOutcomeCount(),
                before.negativeOutcomeCount(),
                decision.negativeOutcomeCount() - before.negativeOutcomeCount(),
                before.editOutcomeCount(),
                decision.editOutcomeCount() - before.editOutcomeCount(),
                before.retryOutcomeCount(),
                decision.retryOutcomeCount() - before.retryOutcomeCount(),
                before.status(),
                before.reviewStatus(),
                rowNo
        );
    }

    private List<UsageRow> select(List<UsageRow> usages, String outcomeType, String aspect) {
        if (!"NEGATIVE".equals(outcomeType)) {
            return usages;
        }
        if ("CONTENT".equals(aspect)) {
            return List.of();
        }
        if ("UNKNOWN".equals(aspect) || aspect == null) {
            return usages;
        }
        return usages.stream().filter(row -> slotMatches(row.slotKey(), aspect)).toList();
    }

    private boolean slotMatches(String slot, String aspect) {
        if (slot == null || slot.isBlank()) {
            return false;
        }
        return switch (aspect) {
            case "LENGTH" -> "answer.length".equals(slot);
            case "LANGUAGE" -> "answer.language".equals(slot);
            case "FORMAT" -> slot.startsWith("format.");
            case "STYLE" -> "answer.style".equals(slot) || "answer.constraint".equals(slot)
                    || slot.startsWith("interaction.");
            default -> false;
        };
    }

    private String degradation(
            List<UsageRow> usages,
            List<UsageRow> selected,
            String outcomeType,
            MemoryFeedbackAspectJudge.Judgment judgment
    ) {
        if (usages.isEmpty()) {
            return NO_APPLICATION;
        }
        if (judgment.degradationReason() != null) {
            return judgment.degradationReason();
        }
        if ("NEGATIVE".equals(outcomeType)
                && !"CONTENT".equals(judgment.aspect())
                && !"UNKNOWN".equals(judgment.aspect())
                && selected.isEmpty()) {
            return SLOT_UNMATCHED;
        }
        return null;
    }

    private double updateWeight(String outcomeType, String aspect) {
        return "NEGATIVE".equals(outcomeType) && "UNKNOWN".equals(aspect) ? 0.5 : 1.0;
    }

    private boolean betaFor(String outcomeType, String aspect) {
        return "NEGATIVE".equals(outcomeType) && !"CONTENT".equals(aspect);
    }

    private double betaWeight(String memoryItemId, boolean unknown) {
        Integer sessions = jdbcTemplate.queryForObject("""
                select count(distinct session_key) from memory_evidence_session where memory_item_id = ?
                """, Integer.class, memoryItemId);
        Integer targets = jdbcTemplate.queryForObject("""
                select count(distinct concat(target_type, ':', target_id))
                from memory_application_outcome
                where memory_item_id = ? and superseded_at is null and beta_weight > 0
                """, Integer.class, memoryItemId);
        int recorded = (sessions == null ? 0 : sessions) + (targets == null ? 0 : targets);
        double weight = MemoryEvidence.weight(MemoryEvidence.Band.INFERRED, recorded);
        if (unknown) {
            weight = weight * 0.5;
        }
        return round4(weight);
    }

    private boolean alreadySignaled(UsageRow usage) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from memory_application_outcome
                where memory_item_id = ? and target_type = ? and target_id = ?
                  and signal_kind in ('RATING', 'RETRY', 'FOLLOW_UP')
                  and superseded_at is null
                """, Integer.class, usage.memoryItemId(), usage.targetType(), usage.targetId());
        return count != null && count > 0;
    }

    private boolean sameRating(
            List<EventRow> active,
            List<UsageRow> selected,
            String outcomeType,
            String note,
            String aspect
    ) {
        if (active.isEmpty()) {
            return false;
        }
        String signature = signature(outcomeType, note, aspect);
        if (active.stream().anyMatch(row -> !signature.equals(signature(row.outcomeType(), row.feedbackNote(), row.aspect())))) {
            return false;
        }
        return itemIds(active).equals(itemIdsOf(selected));
    }

    private boolean sameMagnitude(List<EventRow> active, List<UsageRow> usages, double magnitude) {
        if (active.isEmpty() || active.size() != usages.size()) {
            return false;
        }
        double rounded = round4(magnitude);
        return active.stream().allMatch(row -> row.editMagnitude() != null
                && Math.abs(row.editMagnitude() - rounded) < 0.00005)
                && itemIds(active).equals(itemIdsOf(usages));
    }

    private String requireAnswerMessageId(String workspaceId, String runId) {
        Integer found = jdbcTemplate.queryForObject("""
                select count(*) from answer_run
                where workspace_id = ? and id = ?
                """, Integer.class, workspaceId, runId);
        if (found == null || found == 0) {
            throw new BusinessException("ANSWER_RUN_NOT_FOUND", "回答运行不存在", HttpStatus.NOT_FOUND);
        }
        return optionalAnswerMessageId(workspaceId, runId);
    }

    private String optionalAnswerMessageId(String workspaceId, String runId) {
        return jdbcTemplate.query("""
                select answer_message_id from answer_run
                where workspace_id = ? and id = ?
                for update
                """, rs -> {
            if (!rs.next()) {
                return null;
            }
            String messageId = rs.getString(1);
            return messageId == null || messageId.isBlank() ? null : messageId;
        }, workspaceId, runId);
    }

    private String previousAssistantId(String workspaceId, String userMessageId) {
        return jdbcTemplate.query("""
                select earlier.id
                from conversation_message current_message
                join conversation_message earlier
                  on earlier.conversation_id = current_message.conversation_id
                 and earlier.workspace_id = current_message.workspace_id
                where current_message.id = ? and current_message.workspace_id = ?
                  and earlier.role = 'ASSISTANT'
                  and earlier.message_seq < current_message.message_seq
                order by earlier.message_seq desc
                limit 1
                """, rs -> rs.next() ? rs.getString(1) : null, userMessageId, workspaceId);
    }

    private List<UsageRow> loadUsages(String workspaceId, String targetType, String targetId) {
        return jdbcTemplate.query("""
                select u.id, u.memory_item_id, u.memory_revision_id, u.target_type, u.target_id,
                       i.semantic_slot_key
                from memory_usage_log u
                join memory_item i on i.id = u.memory_item_id
                where u.workspace_id = ? and u.target_type = ? and u.target_id = ?
                  and u.memory_item_id is not null
                order by u.id
                for update
                """, (rs, rowNum) -> new UsageRow(
                rs.getString("id"),
                rs.getString("memory_item_id"),
                rs.getString("memory_revision_id"),
                rs.getString("target_type"),
                rs.getString("target_id"),
                rs.getString("semantic_slot_key")
        ), workspaceId, targetType, targetId);
    }

    private List<EventRow> loadActive(String workspaceId, String targetType, String targetId, String signalKind) {
        return jdbcTemplate.query("""
                select id, workspace_id, usage_log_id, memory_item_id, outcome_type, aspect,
                       feedback_note, beta_weight, edit_magnitude, utility_before, utility_delta,
                       application_count_before, application_delta, positive_count_before, positive_delta,
                       negative_count_before, negative_delta, edit_count_before, edit_delta,
                       retry_count_before, retry_delta, status_before, review_before, row_no,
                       degradation_reason
                from memory_application_outcome
                where workspace_id = ? and target_type = ? and target_id = ?
                  and signal_kind = ? and superseded_at is null
                order by row_no
                for update
                """, (rs, rowNum) -> new EventRow(
                rs.getString("id"),
                rs.getString("workspace_id"),
                rs.getString("usage_log_id"),
                rs.getString("memory_item_id"),
                rs.getString("outcome_type"),
                rs.getString("aspect"),
                rs.getString("feedback_note"),
                rs.getDouble("beta_weight"),
                rs.getObject("edit_magnitude") == null ? null : rs.getDouble("edit_magnitude"),
                rs.getDouble("utility_before"),
                rs.getDouble("utility_delta"),
                rs.getInt("application_count_before"),
                rs.getInt("application_delta"),
                rs.getInt("positive_count_before"),
                rs.getInt("positive_delta"),
                rs.getInt("negative_count_before"),
                rs.getInt("negative_delta"),
                rs.getInt("edit_count_before"),
                rs.getInt("edit_delta"),
                rs.getInt("retry_count_before"),
                rs.getInt("retry_delta"),
                rs.getString("status_before"),
                rs.getString("review_before"),
                rs.getLong("row_no"),
                rs.getString("degradation_reason")
        ), workspaceId, targetType, targetId, signalKind);
    }

    private ItemState lockItem(String workspaceId, String memoryItemId) {
        List<ItemState> states = jdbcTemplate.query("""
                select id, legacy_memory_object_id, utility_score, application_count,
                       positive_outcome_count, negative_outcome_count, edit_outcome_count,
                       retry_outcome_count, status, review_status
                from memory_item
                where workspace_id = ? and id = ?
                for update
                """, (rs, rowNum) -> new ItemState(
                workspaceId,
                rs.getString("id"),
                rs.getString("legacy_memory_object_id"),
                rs.getDouble("utility_score"),
                rs.getInt("application_count"),
                rs.getInt("positive_outcome_count"),
                rs.getInt("negative_outcome_count"),
                rs.getInt("edit_outcome_count"),
                rs.getInt("retry_outcome_count"),
                rs.getString("status"),
                rs.getString("review_status")
        ), workspaceId, memoryItemId);
        if (states.isEmpty()) {
            throw new BusinessException("MEMORY_OBJECT_NOT_FOUND", "Memory object 不存在");
        }
        return states.get(0);
    }

    private void writeItem(String workspaceId, ItemState before, MemoryOutcomePolicy.Decision decision) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("""
                update memory_item
                set utility_score = ?, application_count = ?,
                    positive_outcome_count = ?, negative_outcome_count = ?,
                    edit_outcome_count = ?, retry_outcome_count = ?,
                    status = ?, review_status = ?, outcome_policy_version = ?,
                    last_outcome_at = ?, updated_at = current_timestamp
                where workspace_id = ? and id = ?
                """,
                decision.utilityScore(), decision.applicationCount(), decision.positiveOutcomeCount(),
                decision.negativeOutcomeCount(), decision.editOutcomeCount(), decision.retryOutcomeCount(),
                decision.objectStatus(), decision.reviewStatus(), decision.policyVersion(), now,
                workspaceId, before.memoryItemId());
        if (before.legacyMemoryObjectId() != null) {
            jdbcTemplate.update("""
                    update memory_object
                    set utility_score = ?, application_count = ?,
                        positive_outcome_count = ?, negative_outcome_count = ?,
                        edit_outcome_count = ?, retry_outcome_count = ?,
                        status = ?, review_status = ?, outcome_policy_version = ?,
                        last_outcome_at = ?, updated_at = current_timestamp
                    where workspace_id = ? and id = ?
                    """,
                    decision.utilityScore(), decision.applicationCount(), decision.positiveOutcomeCount(),
                    decision.negativeOutcomeCount(), decision.editOutcomeCount(), decision.retryOutcomeCount(),
                    decision.objectStatus(), decision.reviewStatus(), decision.policyVersion(), now,
                    workspaceId, before.legacyMemoryObjectId());
        }
    }

    private void restore(ItemState current, EventRow row) {
        Timestamp lastOutcome = row.applicationCountBefore() == 0 ? null : Timestamp.from(Instant.now());
        jdbcTemplate.update("""
                update memory_item
                set utility_score = ?, application_count = ?,
                    positive_outcome_count = ?, negative_outcome_count = ?,
                    edit_outcome_count = ?, retry_outcome_count = ?,
                    status = ?, review_status = ?, last_outcome_at = ?,
                    updated_at = current_timestamp
                where workspace_id = ? and id = ?
                """,
                row.utilityBefore(), row.applicationCountBefore(), row.positiveCountBefore(),
                row.negativeCountBefore(), row.editCountBefore(), row.retryCountBefore(),
                row.statusBefore(), row.reviewBefore(), lastOutcome,
                current.workspaceId(), current.memoryItemId());
        if (current.legacyMemoryObjectId() != null) {
            jdbcTemplate.update("""
                    update memory_object
                    set utility_score = ?, application_count = ?,
                        positive_outcome_count = ?, negative_outcome_count = ?,
                        edit_outcome_count = ?, retry_outcome_count = ?,
                        status = ?, review_status = ?, last_outcome_at = ?,
                        updated_at = current_timestamp
                    where workspace_id = ? and id = ?
                    """,
                    row.utilityBefore(), row.applicationCountBefore(), row.positiveCountBefore(),
                    row.negativeCountBefore(), row.editCountBefore(), row.retryCountBefore(),
                    row.statusBefore(), row.reviewBefore(), lastOutcome,
                    current.workspaceId(), current.legacyMemoryObjectId());
        }
    }

    private void adjust(ItemState current, EventRow row) {
        jdbcTemplate.update("""
                update memory_item
                set utility_score = ?, application_count = ?,
                    positive_outcome_count = ?, negative_outcome_count = ?,
                    edit_outcome_count = ?, retry_outcome_count = ?,
                    updated_at = current_timestamp
                where workspace_id = ? and id = ?
                """,
                round4(clamp(current.utilityScore() - row.utilityDelta())),
                Math.max(0, current.applicationCount() - row.applicationDelta()),
                Math.max(0, current.positiveOutcomeCount() - row.positiveDelta()),
                Math.max(0, current.negativeOutcomeCount() - row.negativeDelta()),
                Math.max(0, current.editOutcomeCount() - row.editDelta()),
                Math.max(0, current.retryOutcomeCount() - row.retryDelta()),
                current.workspaceId(), current.memoryItemId());
        if (current.legacyMemoryObjectId() != null) {
            jdbcTemplate.update("""
                    update memory_object
                    set utility_score = ?, application_count = ?,
                        positive_outcome_count = ?, negative_outcome_count = ?,
                        edit_outcome_count = ?, retry_outcome_count = ?,
                        updated_at = current_timestamp
                    where workspace_id = ? and id = ?
                    """,
                    round4(clamp(current.utilityScore() - row.utilityDelta())),
                    Math.max(0, current.applicationCount() - row.applicationDelta()),
                    Math.max(0, current.positiveOutcomeCount() - row.positiveDelta()),
                    Math.max(0, current.negativeOutcomeCount() - row.negativeDelta()),
                    Math.max(0, current.editOutcomeCount() - row.editDelta()),
                    Math.max(0, current.retryOutcomeCount() - row.retryDelta()),
                    current.workspaceId(), current.legacyMemoryObjectId());
        }
    }

    private void writeUsage(
            String workspaceId,
            String usageId,
            MemoryOutcomePolicy.Decision decision,
            Double editMagnitude,
            String note
    ) {
        jdbcTemplate.update("""
                update memory_usage_log
                set outcome_type = ?, outcome_score = ?, edit_magnitude = ?,
                    feedback_note = ?, outcome_policy_version = ?, outcome_at = ?,
                    effect_feedback = ?
                where workspace_id = ? and id = ? and outcome_type is null
                """,
                decision.outcomeType(), decision.outcomeScore(), editMagnitude, note,
                decision.policyVersion(), Timestamp.from(Instant.now()), decision.outcomeType(),
                workspaceId, usageId);
    }

    private void clearUsage(EventRow row) {
        if (row.usageLogId() == null) {
            return;
        }
        jdbcTemplate.update("""
                update memory_usage_log
                set outcome_type = null, outcome_score = null, edit_magnitude = null,
                    feedback_note = null, outcome_at = null, effect_feedback = null
                where workspace_id = ? and id = ? and outcome_type = ?
                """, row.workspaceId(), row.usageLogId(), row.outcomeType());
    }

    private void addBeta(String memoryItemId, double weight) {
        jdbcTemplate.update("""
                update memory_item
                set evidence_beta = evidence_beta + ?, updated_at = current_timestamp
                where id = ?
                """, weight, memoryItemId);
    }

    private void subtractBeta(String memoryItemId, double weight) {
        jdbcTemplate.update("""
                update memory_item
                set evidence_beta = case when evidence_beta < ? then 0 else evidence_beta - ? end,
                    updated_at = current_timestamp
                where id = ?
                """, weight, weight, memoryItemId);
    }

    private int laterUtilityCount(EventRow row) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from memory_application_outcome
                where memory_item_id = ? and superseded_at is null and row_no > ?
                  and (application_delta <> 0 or utility_delta <> 0)
                """, Integer.class, row.memoryItemId(), row.rowNo());
        return count == null ? 0 : count;
    }

    private void supersede(String id) {
        jdbcTemplate.update("""
                update memory_application_outcome
                set superseded_at = current_timestamp
                where id = ? and superseded_at is null
                """, id);
    }

    private boolean sourceExists(String workspaceId, String sourceRef) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from memory_application_outcome
                where workspace_id = ? and source_ref = ?
                """, Integer.class, workspaceId, sourceRef);
        return count != null && count > 0;
    }

    private long nextRowNo(String memoryItemId) {
        Long current = jdbcTemplate.queryForObject("""
                select coalesce(max(row_no), 0) from memory_application_outcome where memory_item_id = ?
                """, Long.class, memoryItemId);
        return (current == null ? 0 : current) + 1;
    }

    private long nextTargetRowNo(String workspaceId, String targetType, String targetId, String signalKind) {
        Long current = jdbcTemplate.queryForObject("""
                select coalesce(max(row_no), 0) from memory_application_outcome
                where workspace_id = ? and target_type = ? and target_id = ? and signal_kind = ?
                """, Long.class, workspaceId, targetType, targetId, signalKind);
        return (current == null ? 0 : current) + 1;
    }

    private String normalizeRating(String rating) {
        String value = rating == null ? "" : rating.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"POSITIVE".equals(value) && !"NEGATIVE".equals(value)) {
            throw new BusinessException("MEMORY_FEEDBACK_RATING_INVALID", "不支持的回答反馈: " + value);
        }
        return value;
    }

    private String trimNote(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500);
    }

    private String firstDegradation(List<EventRow> rows) {
        for (EventRow row : rows) {
            if (row.degradationReason() != null) {
                return row.degradationReason();
            }
        }
        return null;
    }

    private int countItems(List<EventRow> rows) {
        return itemIds(rows).size();
    }

    private Set<String> itemIds(List<EventRow> rows) {
        Set<String> ids = new HashSet<>();
        for (EventRow row : rows) {
            if (row.memoryItemId() != null) {
                ids.add(row.memoryItemId());
            }
        }
        return ids;
    }

    private Set<String> itemIdsOf(List<UsageRow> rows) {
        Set<String> ids = new HashSet<>();
        for (UsageRow row : rows) {
            ids.add(row.memoryItemId());
        }
        return ids;
    }

    private String signature(String outcomeType, String note, String aspect) {
        return outcomeType + "\n" + (note == null ? "" : note) + "\n" + (aspect == null ? "" : aspect);
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private record UsageRow(
            String usageId,
            String memoryItemId,
            String memoryRevisionId,
            String targetType,
            String targetId,
            String slotKey
    ) {
    }

    private record EventRow(
            String id,
            String workspaceId,
            String usageLogId,
            String memoryItemId,
            String outcomeType,
            String aspect,
            String feedbackNote,
            double betaWeight,
            Double editMagnitude,
            double utilityBefore,
            double utilityDelta,
            int applicationCountBefore,
            int applicationDelta,
            int positiveCountBefore,
            int positiveDelta,
            int negativeCountBefore,
            int negativeDelta,
            int editCountBefore,
            int editDelta,
            int retryCountBefore,
            int retryDelta,
            String statusBefore,
            String reviewBefore,
            long rowNo,
            String degradationReason
    ) {
    }

    private record ItemState(
            String workspaceId,
            String memoryItemId,
            String legacyMemoryObjectId,
            double utilityScore,
            int applicationCount,
            int positiveOutcomeCount,
            int negativeOutcomeCount,
            int editOutcomeCount,
            int retryOutcomeCount,
            String status,
            String reviewStatus
    ) {
        private MemoryOutcomePolicy.State toPolicyState() {
            return new MemoryOutcomePolicy.State(
                    utilityScore,
                    applicationCount,
                    positiveOutcomeCount,
                    negativeOutcomeCount,
                    editOutcomeCount,
                    retryOutcomeCount,
                    status,
                    reviewStatus
            );
        }
    }
}
