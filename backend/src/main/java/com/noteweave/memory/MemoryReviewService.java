package com.noteweave.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.noteweave.security.CurrentUserProvider;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemoryReviewService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final CurrentUserProvider currentUserProvider;
    private final MemoryPromotionService promotionService;
    private final MemoryVersionService versionService;
    private final MemoryStatementMatcher statementMatcher;

    public MemoryReviewService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            WorkspaceAccessGuard workspaceAccessGuard,
            CurrentUserProvider currentUserProvider,
            MemoryPromotionService promotionService,
            MemoryVersionService versionService,
            MemoryStatementMatcher statementMatcher
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.currentUserProvider = currentUserProvider;
        this.promotionService = promotionService;
        this.versionService = versionService;
        this.statementMatcher = statementMatcher;
    }

    public List<MemoryReviewItemResponse> listQueue(
            String workspaceId,
            String reviewKind,
            int limit
    ) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.MEMORY_REVIEW);
        String normalizedKind = MemorySignalService.normalizeToken(reviewKind);
        if (!Set.of("ALL", "CANDIDATE", "OBJECT").contains(normalizedKind)) {
            throw new BusinessException(
                    "MEMORY_REVIEW_KIND_INVALID",
                    "不支持的 Memory review kind: " + normalizedKind
            );
        }
        String userId = currentUserProvider.requireUserId();
        List<MemoryReviewItemResponse> items = new ArrayList<>();
        if (!"OBJECT".equals(normalizedKind)) {
            items.addAll(listCandidateReviews(workspaceId, userId));
        }
        if (!"CANDIDATE".equals(normalizedKind)) {
            items.addAll(listObjectReviews(workspaceId, userId));
        }
        return items.stream()
                .sorted(Comparator.comparingInt(MemoryReviewItemResponse::priority).reversed()
                        .thenComparing(MemoryReviewItemResponse::createdAt)
                        .thenComparing(MemoryReviewItemResponse::reviewId))
                .limit(Math.max(1, Math.min(limit, 200)))
                .toList();
    }

    @Transactional
    public MemoryReviewDecisionResponse decide(
            String workspaceId,
            String reviewKind,
            String reviewId,
            MemoryReviewDecisionRequest request
    ) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.MEMORY_REVIEW);
        String normalizedKind = MemorySignalService.normalizeToken(reviewKind);
        return switch (normalizedKind) {
            case "CANDIDATE" -> decideCandidate(workspaceId, reviewId, request);
            case "OBJECT" -> decideObject(workspaceId, reviewId, request);
            default -> throw new BusinessException(
                    "MEMORY_REVIEW_KIND_INVALID",
                    "不支持的 Memory review kind: " + normalizedKind
            );
        };
    }

    private MemoryReviewDecisionResponse decideCandidate(
            String workspaceId,
            String candidateId,
            MemoryReviewDecisionRequest request
    ) {
        String userId = currentUserProvider.requireUserId();
        CandidateReviewRow review = lockCandidate(workspaceId, candidateId, userId);
        if (!"NEEDS_REVIEW".equals(review.reviewStatus())) {
            throw new BusinessException(
                    "MEMORY_REVIEW_ALREADY_DECIDED",
                    "Memory candidate 已完成 review"
            );
        }
        String decision = request.decision();
        if ("REJECT".equals(decision)) {
            jdbcTemplate.update("""
                    update memory_candidate
                    set review_status = 'REJECTED', updated_at = current_timestamp
                    where workspace_id = ? and id = ?
                    """, workspaceId, candidateId);
            return auditResponse(
                    workspaceId, "CANDIDATE", candidateId, decision, request.reason(),
                    "REJECTED", review.stalenessStatus(), null, List.of());
        }
        if ("APPROVE".equals(decision)
                && "CONFLICTING_ACTIVE_MEMORY".equals(review.conflictStatus())) {
            throw new BusinessException(
                    "MEMORY_REVIEW_CONFLICT_RESOLUTION_REQUIRED",
                    "冲突 Candidate 必须显式选择 REPLACE_EXISTING 或 REJECT"
            );
        }
        List<String> revokedObjectIds = List.of();
        if ("REPLACE_EXISTING".equals(decision)) {
            if (!"CONFLICTING_ACTIVE_MEMORY".equals(review.conflictStatus())) {
                throw new BusinessException(
                        "MEMORY_REVIEW_DECISION_INVALID",
                        "只有冲突 Candidate 可以使用 REPLACE_EXISTING"
                );
            }
            revokedObjectIds = findConflictingActiveObjects(workspaceId, review);
            if (revokedObjectIds.isEmpty()) {
                throw new BusinessException(
                        "MEMORY_REVIEW_CONFLICT_TARGET_NOT_FOUND",
                        "未找到仍处于 ACTIVE 的冲突 Memory"
                );
            }
            for (String memoryObjectId : revokedObjectIds) {
                versionService.revoke(workspaceId, memoryObjectId);
            }
            jdbcTemplate.update("""
                    update memory_candidate
                    set conflict_status = 'RESOLVED_REPLACE', review_status = 'READY',
                        updated_at = current_timestamp
                    where workspace_id = ? and id = ?
                    """, workspaceId, candidateId);
        } else if ("APPROVE".equals(decision)) {
            jdbcTemplate.update("""
                    update memory_candidate
                    set review_status = 'READY', updated_at = current_timestamp
                    where workspace_id = ? and id = ?
                    """, workspaceId, candidateId);
        } else {
            throw new BusinessException(
                    "MEMORY_REVIEW_DECISION_INVALID",
                    "Candidate review 仅支持 APPROVE、REJECT、REPLACE_EXISTING"
            );
        }
        MemoryObjectResponse promoted = promotionService.promoteCandidate(workspaceId, candidateId);
        return auditResponse(
                workspaceId, "CANDIDATE", candidateId, decision, request.reason(),
                "PROMOTED", "ACTIVE", promoted, revokedObjectIds);
    }

    private MemoryReviewDecisionResponse decideObject(
            String workspaceId,
            String memoryObjectId,
            MemoryReviewDecisionRequest request
    ) {
        String userId = currentUserProvider.requireUserId();
        ObjectReviewRow review = lockObject(workspaceId, memoryObjectId, userId);
        if (!"REVIEW_REQUIRED".equals(review.reviewStatus())
                && !"STALE".equals(review.status())) {
            throw new BusinessException(
                    "MEMORY_REVIEW_ALREADY_DECIDED",
                    "Memory object 当前不需要 review"
            );
        }
        if ("APPROVE".equals(request.decision())) {
            jdbcTemplate.update("""
                    update memory_object
                    set status = 'ACTIVE', review_status = 'APPROVED',
                        updated_at = current_timestamp
                    where workspace_id = ? and id = ?
                    """, workspaceId, memoryObjectId);
            jdbcTemplate.update("""
                    update memory_item
                    set status = 'ACTIVE', review_status = 'APPROVED',
                        updated_at = current_timestamp
                    where workspace_id = ? and legacy_memory_object_id = ?
                    """, workspaceId, memoryObjectId);
            return auditResponse(
                    workspaceId, "OBJECT", memoryObjectId, request.decision(),
                    request.reason(), "APPROVED", "ACTIVE", null, List.of());
        }
        if ("REVOKE".equals(request.decision())) {
            versionService.revoke(workspaceId, memoryObjectId);
            jdbcTemplate.update("""
                    update memory_object
                    set review_status = 'REJECTED', updated_at = current_timestamp
                    where workspace_id = ? and id = ?
                    """, workspaceId, memoryObjectId);
            jdbcTemplate.update("""
                    update memory_item
                    set review_status = 'REJECTED', updated_at = current_timestamp
                    where workspace_id = ? and legacy_memory_object_id = ?
                    """, workspaceId, memoryObjectId);
            return auditResponse(
                    workspaceId, "OBJECT", memoryObjectId, request.decision(),
                    request.reason(), "REJECTED", "REVOKED", null,
                    List.of(memoryObjectId));
        }
        throw new BusinessException(
                "MEMORY_REVIEW_DECISION_INVALID",
                "Object review 仅支持 APPROVE、REVOKE"
        );
    }

    private MemoryReviewDecisionResponse auditResponse(
            String workspaceId,
            String reviewKind,
            String reviewId,
            String decision,
            String reason,
            String reviewStatus,
            String lifecycleStatus,
            MemoryObjectResponse promoted,
            List<String> revokedObjectIds
    ) {
        String auditId = Ids.newId();
        jdbcTemplate.update("""
                insert into memory_review_decision(
                    id, workspace_id, review_kind, review_id, decision, reason,
                    actor_user_id, result_object_id, revoked_object_ids_json
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                auditId,
                workspaceId,
                reviewKind,
                reviewId,
                decision,
                reason,
                currentUserProvider.requireUserId(),
                promoted == null ? null : promoted.memoryObjectId(),
                Json.write(objectMapper, revokedObjectIds)
        );
        return new MemoryReviewDecisionResponse(
                auditId,
                reviewKind,
                reviewId,
                decision,
                reviewStatus,
                lifecycleStatus,
                promoted,
                List.copyOf(revokedObjectIds)
        );
    }

    private List<MemoryReviewItemResponse> listCandidateReviews(
            String workspaceId,
            String userId
    ) {
        return jdbcTemplate.query("""
                select id, workspace_id, normalized_statement, task_neighborhood_json,
                       review_status, staleness_status, conflict_status,
                       evidence_gate_status, risk_score, marginal_utility_score,
                       policy_version, created_at, updated_at
                from memory_candidate
                where workspace_id = ? and user_id = ? and review_status = 'NEEDS_REVIEW'
                """, (rs, rowNum) -> new MemoryReviewItemResponse(
                "CANDIDATE",
                rs.getString("id"),
                rs.getString("workspace_id"),
                rs.getString("normalized_statement"),
                readStringList(rs.getString("task_neighborhood_json")),
                rs.getString("review_status"),
                rs.getString("staleness_status"),
                rs.getString("conflict_status"),
                rs.getString("evidence_gate_status"),
                rs.getDouble("risk_score"),
                rs.getDouble("marginal_utility_score"),
                rs.getString("policy_version"),
                null,
                "CONFLICTING_ACTIVE_MEMORY".equals(rs.getString("conflict_status")) ? 100 : 70,
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        ), workspaceId, userId);
    }

    private List<MemoryReviewItemResponse> listObjectReviews(
            String workspaceId,
            String userId
    ) {
        return jdbcTemplate.query("""
                select id, workspace_id, canonical_statement, task_neighborhood_json,
                       review_status, status, utility_score, outcome_policy_version,
                       latest_version_id, created_at, updated_at
                from memory_object
                where workspace_id = ?
                  and (memory_scope = 'WORKSPACE' or user_id = ?)
                  and status <> 'REVOKED'
                  and (review_status = 'REVIEW_REQUIRED' or status = 'STALE')
                """, (rs, rowNum) -> new MemoryReviewItemResponse(
                "OBJECT",
                rs.getString("id"),
                rs.getString("workspace_id"),
                rs.getString("canonical_statement"),
                readStringList(rs.getString("task_neighborhood_json")),
                rs.getString("review_status"),
                rs.getString("status"),
                "NO_CONFLICT",
                "PASS",
                0.0,
                rs.getDouble("utility_score"),
                rs.getString("outcome_policy_version"),
                rs.getString("latest_version_id"),
                "STALE".equals(rs.getString("status")) ? 90 : 80,
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        ), workspaceId, userId);
    }

    private CandidateReviewRow lockCandidate(
            String workspaceId,
            String candidateId,
            String userId
    ) {
        List<CandidateReviewRow> rows = jdbcTemplate.query("""
                select id, candidate_type, normalized_statement, task_neighborhood_json,
                       conflict_status, staleness_status, review_status
                from memory_candidate
                where workspace_id = ? and id = ? and user_id = ?
                for update
                """, (rs, rowNum) -> new CandidateReviewRow(
                rs.getString("id"),
                rs.getString("candidate_type"),
                rs.getString("normalized_statement"),
                readStringList(rs.getString("task_neighborhood_json")),
                rs.getString("conflict_status"),
                rs.getString("staleness_status"),
                rs.getString("review_status")
        ), workspaceId, candidateId, userId);
        if (rows.isEmpty()) {
            throw new BusinessException("MEMORY_REVIEW_NOT_FOUND", "Memory candidate review 不存在");
        }
        return rows.get(0);
    }

    private ObjectReviewRow lockObject(
            String workspaceId,
            String memoryObjectId,
            String userId
    ) {
        List<ObjectReviewRow> rows = jdbcTemplate.query("""
                select id, status, review_status
                from memory_object
                where workspace_id = ? and id = ?
                  and (memory_scope = 'WORKSPACE' or user_id = ?)
                for update
                """, (rs, rowNum) -> new ObjectReviewRow(
                rs.getString("id"),
                rs.getString("status"),
                rs.getString("review_status")
        ), workspaceId, memoryObjectId, userId);
        if (rows.isEmpty()) {
            throw new BusinessException("MEMORY_REVIEW_NOT_FOUND", "Memory object review 不存在");
        }
        return rows.get(0);
    }

    private List<String> findConflictingActiveObjects(
            String workspaceId,
            CandidateReviewRow candidate
    ) {
        List<ConflictObjectRow> rows = jdbcTemplate.query("""
                select id, memory_type, canonical_statement, task_neighborhood_json
                from memory_object
                where workspace_id = ? and status = 'ACTIVE'
                """, (rs, rowNum) -> new ConflictObjectRow(
                rs.getString("id"),
                rs.getString("memory_type"),
                rs.getString("canonical_statement"),
                readStringList(rs.getString("task_neighborhood_json"))
        ), workspaceId);
        List<String> conflicts = new ArrayList<>();
        for (ConflictObjectRow row : rows) {
            Set<String> overlap = new LinkedHashSet<>(row.taskNeighborhoods());
            overlap.retainAll(candidate.taskNeighborhoods());
            if (!overlap.isEmpty()
                    && !row.memoryType().equals(candidate.candidateType())
                    && statementMatcher.equivalent(
                    row.canonicalStatement(), candidate.statement())) {
                conflicts.add(row.memoryObjectId());
            }
        }
        return List.copyOf(conflicts);
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new BusinessException("MEMORY_JSON_PARSE_FAILED", "Memory JSON 解析失败");
        }
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record CandidateReviewRow(
            String candidateId,
            String candidateType,
            String statement,
            List<String> taskNeighborhoods,
            String conflictStatus,
            String stalenessStatus,
            String reviewStatus
    ) {
    }

    private record ObjectReviewRow(
            String memoryObjectId,
            String status,
            String reviewStatus
    ) {
    }

    private record ConflictObjectRow(
            String memoryObjectId,
            String memoryType,
            String canonicalStatement,
            List<String> taskNeighborhoods
    ) {
    }
}
