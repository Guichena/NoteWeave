package com.noteweave.memory;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemoryOutcomeService {

    private final JdbcTemplate jdbcTemplate;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final MemoryOutcomePolicy outcomePolicy;

    public MemoryOutcomeService(
            JdbcTemplate jdbcTemplate,
            WorkspaceAccessGuard workspaceAccessGuard,
            MemoryOutcomePolicy outcomePolicy
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.outcomePolicy = outcomePolicy;
    }

    @Transactional
    public MemoryOutcomeBatchResponse recordOutcome(
            String workspaceId,
            CreateMemoryOutcomeRequest request
    ) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.MEMORY_REVIEW);
        List<ApplicationRow> applications = jdbcTemplate.query("""
                select id, memory_item_id, memory_revision_id, memory_object_id
                from memory_usage_log
                where workspace_id = ? and target_type = ? and target_id = ?
                  and outcome_type is null
                order by used_at asc, id asc
                for update
                """, (rs, rowNum) -> new ApplicationRow(
                rs.getString("id"),
                rs.getString("memory_item_id"),
                rs.getString("memory_revision_id"),
                rs.getString("memory_object_id")
        ), workspaceId, request.targetType(), request.targetId());
        if (applications.isEmpty()) {
            throw new BusinessException(
                    "MEMORY_APPLICATION_NOT_FOUND",
                    "目标没有待反馈的 Memory application"
            );
        }

        Instant outcomeAt = Instant.now();
        List<MemoryApplicationOutcomeResponse> outcomes = new ArrayList<>();
        for (ApplicationRow application : applications) {
            ObjectState state = lockItem(workspaceId, application.memoryItemId());
            MemoryOutcomePolicy.Decision decision = outcomePolicy.evaluate(
                    state.toPolicyState(), request.outcomeType(), request.editMagnitude());
            // MEM-07：先留等价事件（before/delta）再改计数，两条路径共用一份事件日志。
            recordOutcomeEvent(workspaceId, request, application, state, decision);
            int usageUpdated = jdbcTemplate.update("""
                    update memory_usage_log
                    set outcome_type = ?, outcome_score = ?, edit_magnitude = ?,
                        feedback_note = ?, outcome_policy_version = ?, outcome_at = ?,
                        effect_feedback = ?
                    where workspace_id = ? and id = ? and outcome_type is null
                    """,
                    decision.outcomeType(),
                    decision.outcomeScore(),
                    request.editMagnitude(),
                    request.feedbackNote(),
                    decision.policyVersion(),
                    Timestamp.from(outcomeAt),
                    decision.outcomeType(),
                    workspaceId,
                    application.applicationId()
            );
            if (usageUpdated != 1) {
                throw new BusinessException(
                        "MEMORY_OUTCOME_ALREADY_RECORDED",
                        "Memory application 已记录 outcome"
                );
            }
            jdbcTemplate.update("""
                    update memory_item
                    set utility_score = ?, application_count = ?,
                        positive_outcome_count = ?, negative_outcome_count = ?,
                        edit_outcome_count = ?, retry_outcome_count = ?,
                        status = ?, review_status = ?, outcome_policy_version = ?,
                        last_outcome_at = ?, updated_at = current_timestamp
                    where workspace_id = ? and id = ?
                    """,
                    decision.utilityScore(),
                    decision.applicationCount(),
                    decision.positiveOutcomeCount(),
                    decision.negativeOutcomeCount(),
                    decision.editOutcomeCount(),
                    decision.retryOutcomeCount(),
                    decision.objectStatus(),
                    decision.reviewStatus(),
                    decision.policyVersion(),
                    Timestamp.from(outcomeAt),
                    workspaceId,
                    application.memoryItemId()
            );
            if (application.legacyMemoryObjectId() != null) {
                jdbcTemplate.update("""
                        update memory_object
                        set utility_score = ?, application_count = ?,
                            positive_outcome_count = ?, negative_outcome_count = ?,
                            edit_outcome_count = ?, retry_outcome_count = ?,
                            status = ?, review_status = ?, outcome_policy_version = ?,
                            last_outcome_at = ?, updated_at = current_timestamp
                        where workspace_id = ? and id = ?
                        """,
                        decision.utilityScore(), decision.applicationCount(),
                        decision.positiveOutcomeCount(), decision.negativeOutcomeCount(),
                        decision.editOutcomeCount(), decision.retryOutcomeCount(),
                        decision.objectStatus(), decision.reviewStatus(), decision.policyVersion(),
                        Timestamp.from(outcomeAt), workspaceId, application.legacyMemoryObjectId());
            }
            outcomes.add(new MemoryApplicationOutcomeResponse(
                    application.applicationId(),
                    application.memoryItemId(),
                    application.memoryRevisionId(),
                    decision.outcomeType(),
                    decision.outcomeScore(),
                    state.utilityScore(),
                    decision.utilityScore(),
                    decision.applicationCount(),
                    decision.positiveOutcomeCount(),
                    decision.negativeOutcomeCount(),
                    decision.editOutcomeCount(),
                    decision.retryOutcomeCount(),
                    decision.objectStatus(),
                    decision.reviewStatus(),
                    decision.policyVersion()
            ));
        }
        return new MemoryOutcomeBatchResponse(
                request.targetType(),
                request.targetId(),
                outcomePolicy.version(),
                List.copyOf(outcomes)
        );
    }

    /**
     * MEM-07：旧接口也写一条等价事件（before/delta），与事件式反馈共用 memory_application_outcome。
     * <p>
     * 此前只有事件式路径留痕：旧接口写的 outcome 既无法审计，也无法被撤销路径计入——
     * 之后任何一次撤销都会按旧快照恢复计数，把这次 outcome 抹掉。两条路径现在都是
     * 「一条事件 + 一次绝对写入」，撤销时按事件里的 before/delta 反推，互相不再覆盖。
     */
    private void recordOutcomeEvent(String workspaceId, CreateMemoryOutcomeRequest request,
                                    ApplicationRow application, ObjectState before,
                                    MemoryOutcomePolicy.Decision decision) {
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
                Ids.newId(), workspaceId, application.applicationId(), application.memoryItemId(),
                application.memoryRevisionId(), request.targetType(), request.targetId(),
                "LEGACY_OUTCOME", decision.outcomeType(), decision.outcomeScore(), 1.0,
                0.0, "UNKNOWN", request.feedbackNote(), null,
                "memory-outcome:" + application.applicationId(), request.editMagnitude(),
                before.utilityScore(), round4(decision.utilityScore() - before.utilityScore()),
                before.applicationCount(), decision.applicationCount() - before.applicationCount(),
                before.positiveOutcomeCount(), decision.positiveOutcomeCount() - before.positiveOutcomeCount(),
                before.negativeOutcomeCount(), decision.negativeOutcomeCount() - before.negativeOutcomeCount(),
                before.editOutcomeCount(), decision.editOutcomeCount() - before.editOutcomeCount(),
                before.retryOutcomeCount(), decision.retryOutcomeCount() - before.retryOutcomeCount(),
                before.status(), before.reviewStatus(), nextRowNo(application.memoryItemId()));
    }

    private long nextRowNo(String memoryItemId) {
        Long current = jdbcTemplate.queryForObject("""
                select coalesce(max(row_no), 0) from memory_application_outcome where memory_item_id = ?
                """, Long.class, memoryItemId);
        return (current == null ? 0 : current) + 1;
    }

    private static double round4(double value) {
        return Math.round(value * 10000d) / 10000d;
    }

    private ObjectState lockItem(String workspaceId, String memoryItemId) {
        List<ObjectState> states = jdbcTemplate.query("""
                select id, utility_score, application_count, positive_outcome_count,
                       negative_outcome_count, edit_outcome_count, retry_outcome_count,
                       status, review_status
                from memory_item
                where workspace_id = ? and id = ?
                for update
                """, (rs, rowNum) -> new ObjectState(
                rs.getString("id"),
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

    private record ApplicationRow(
            String applicationId,
            String memoryItemId,
            String memoryRevisionId,
            String legacyMemoryObjectId
    ) {
    }

    private record ObjectState(
            String memoryObjectId,
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
