package com.noteweave.memory;

import com.noteweave.common.BusinessException;
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
