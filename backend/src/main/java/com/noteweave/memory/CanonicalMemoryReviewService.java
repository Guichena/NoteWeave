package com.noteweave.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.noteweave.conversation.RunReplayRedactionService;
import com.noteweave.security.CurrentUserProvider;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CanonicalMemoryReviewService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CurrentUserProvider currentUserProvider;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final MemoryStatementMatcher statementMatcher;
    private final RunReplayRedactionService replayRedactionService;
    private final MemoryIndexOutbox indexOutbox;

    public CanonicalMemoryReviewService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            CurrentUserProvider currentUserProvider,
            WorkspaceAccessGuard workspaceAccessGuard,
            MemoryStatementMatcher statementMatcher,
            RunReplayRedactionService replayRedactionService,
            MemoryIndexOutbox indexOutbox
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.currentUserProvider = currentUserProvider;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.statementMatcher = statementMatcher;
        this.replayRedactionService = replayRedactionService;
        this.indexOutbox = indexOutbox;
    }

    @Transactional
    public MemoryObjectResponse projectCandidate(MemoryCandidateService.CandidateRow candidate) {
        return projectCandidate(candidate, currentUserProvider.requireUserId());
    }

    /** 由系统任务（例如从对话中提取候选）发起时，没有当前登录用户，需要显式给出记忆的所有者。 */
    MemoryObjectResponse projectCandidate(MemoryCandidateService.CandidateRow candidate, String actor) {
        Integer existing = jdbcTemplate.queryForObject(
                "select count(*) from memory_runtime_revision where id = ?",
                Integer.class,
                candidate.candidateId());
        if (existing != null && existing > 0) {
            return candidateResponse(candidate);
        }
        boolean activate = "READY".equals(candidate.reviewStatus());
        jdbcTemplate.update("""
                insert into memory_item(
                    id, workspace_id, owner_user_id, memory_scope, scope_ref_key, slot_key,
                    slot_schema_version, status, current_revision_id, lock_version,
                    utility_score, review_status, outcome_policy_version, semantic_slot_key
                ) values (?, ?, ?, 'WORKSPACE', ?, ?, 'candidate-v3', ?, ?, 0, ?, ?, ?, ?)
                """,
                candidate.candidateId(),
                candidate.workspaceId(),
                actor,
                candidate.workspaceId(),
                "candidate:" + candidate.candidateId(),
                activate ? "ACTIVE" : "EMPTY",
                activate ? candidate.candidateId() : null,
                candidate.marginalUtilityScore(),
                activate ? "APPROVED" : "REVIEW_REQUIRED",
                MemoryOutcomePolicy.VERSION,
                MemorySlots.normalizeSlot(candidate.slotKey()));
        jdbcTemplate.update("""
                insert into memory_runtime_revision(
                    id, memory_item_id, workspace_id, version_no, status, confidence,
                    normalized_value_json, display_text, provenance_type, provenance_ref,
                    observation_id, content_hash
                ) values (?, ?, ?, 1, ?, ?, ?, ?, 'MEMORY_CANDIDATE', ?, ?, ?)
                """,
                candidate.candidateId(),
                candidate.candidateId(),
                candidate.workspaceId(),
                activate ? "ACTIVE" : "PROPOSED",
                candidate.riskScore(),
                candidatePayload(candidate),
                candidate.normalizedStatement(),
                candidate.candidateId(),
                "candidate:" + candidate.candidateId(),
                candidate.candidateId());
        if (activate) {
            jdbcTemplate.update("""
                    update memory_candidate
                    set review_status = 'PROMOTED', updated_at = current_timestamp
                    where workspace_id = ? and id = ?
                    """, candidate.workspaceId(), candidate.candidateId());
            writeEvent(candidate.candidateId(), "CANDIDATE_ACTIVATED", candidate.candidateId());
            enqueueIndex(candidate.workspaceId(), candidate.candidateId(), "UPSERT",
                    candidate.normalizedStatement(), candidate.slotKey(), firstNeighborhood(candidate.taskNeighborhoods()));
        } else {
            writeEvent(candidate.candidateId(), "CANDIDATE_PROPOSED", candidate.candidateId());
            if ("CONFLICTING_ACTIVE_MEMORY".equals(candidate.conflictStatus())
                    || "CONFLICT_CHECK_NOT_RUN".equals(candidate.conflictStatus())) {
                flagConflictingItem(candidate.conflictingItemId());
            }
        }
        return candidateResponse(candidate);
    }

    public List<MemoryRuntimeReviewItemResponse> list(String workspaceId) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.MEMORY_REVIEW);
        return jdbcTemplate.query("""
                select r.id, r.memory_item_id, r.status, r.display_text, r.provenance_ref,
                       r.normalized_value_json, i.utility_score, i.review_status, i.status as lifecycle_status,
                       i.semantic_slot_key
                from memory_runtime_revision r
                join memory_item i on i.id = r.memory_item_id
                where r.workspace_id = ?
                  and i.status not in ('DELETED', 'SUPERSEDED')
                  and (
                    r.status = 'PROPOSED'
                    or (r.id = i.current_revision_id and r.status = 'ACTIVE'
                        and (i.review_status = 'REVIEW_REQUIRED' or i.status = 'STALE'))
                  )
                order by case when r.status = 'PROPOSED' then 0 else 1 end,
                         r.created_at, r.id
                """, (rs, rowNum) -> new MemoryRuntimeReviewItemResponse(
                rs.getString("id"),
                rs.getString("memory_item_id"),
                "PROPOSED".equals(rs.getString("status")) ? "PROPOSAL" : "ACTIVE",
                rs.getString("status"),
                rs.getString("display_text"),
                rs.getString("provenance_ref"),
                payloadText(rs.getString("normalized_value_json"), "conflict_status", "NO_CONFLICT"),
                rs.getDouble("utility_score"),
                rs.getString("review_status"),
                rs.getString("lifecycle_status"),
                displaySlot(rs.getString("semantic_slot_key"), rs.getString("normalized_value_json")),
                conflictOf(rs.getString("normalized_value_json"))
        ), workspaceId);
    }

    @Transactional
    public MemoryRuntimeRevisionResponse review(
            String workspaceId,
            String revisionId,
            MemoryReviewDecisionRequest request
    ) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.MEMORY_REVIEW);
        ReviewRow row = lockReview(workspaceId, revisionId, request.decision());
        return "PROPOSED".equals(row.revisionStatus())
                ? reviewProposal(workspaceId, row, request)
                : reviewActive(workspaceId, row, request);
    }

    private MemoryRuntimeRevisionResponse reviewProposal(
            String workspaceId,
            ReviewRow row,
            MemoryReviewDecisionRequest request
    ) {
        if ("REJECT".equals(request.decision())) {
            jdbcTemplate.update("update memory_runtime_revision set status = 'REJECTED' where id = ?", row.revisionId());
            jdbcTemplate.update("""
                    update memory_item set status = 'DELETED', review_status = 'REJECTED',
                        lock_version = lock_version + 1, updated_at = current_timestamp
                    where id = ?
                    """, row.itemId());
            updateCandidateStatus(row, "REJECTED");
            writeEvent(row.itemId(), "REVISION_REJECTED", row.revisionId(), decisionPayload(request, null));
            return new MemoryRuntimeRevisionResponse(row.revisionId(), row.itemId(), "REJECTED", List.of());
        }

        String conflictStatus = payloadText(row.normalizedValueJson(), "conflict_status", "NO_CONFLICT");
        if ("CONFLICTING_ACTIVE_MEMORY".equals(conflictStatus)
                && !"REPLACE_EXISTING".equals(request.decision())) {
            throw new BusinessException(
                    "MEMORY_REVIEW_CONFLICT_RESOLUTION_REQUIRED",
                    "冲突 Memory proposal 必须显式选择 REPLACE_EXISTING 或 REJECT");
        }
        if (!Set.of("ACCEPT", "REPLACE_EXISTING").contains(request.decision())) {
            throw new BusinessException(
                    "MEMORY_RUNTIME_REVIEW_DECISION_INVALID",
                    "Proposal review 仅支持 ACCEPT、REPLACE_EXISTING 或 REJECT");
        }

        List<String> revoked = "REPLACE_EXISTING".equals(request.decision())
                ? revokeConflictingItems(workspaceId, row, request)
                : List.of();
        if ("REPLACE_EXISTING".equals(request.decision()) && revoked.isEmpty()) {
            throw new BusinessException(
                    "MEMORY_REVIEW_CONFLICT_TARGET_NOT_FOUND",
                    "未找到仍处于 ACTIVE 的冲突 Memory");
        }
        jdbcTemplate.update("""
                update memory_runtime_revision set status = 'ACTIVE', valid_from = current_timestamp
                where id = ?
                """, row.revisionId());
        jdbcTemplate.update("""
                update memory_item set current_revision_id = ?, status = 'ACTIVE', review_status = 'APPROVED',
                    semantic_slot_key = ?,
                    lock_version = lock_version + 1, last_confirmed_at = current_timestamp,
                    updated_at = current_timestamp where id = ?
                """, row.revisionId(), MemorySlots.normalizeSlot(payloadText(row.normalizedValueJson(), "slot", "")),
                row.itemId());
        updateCandidateStatus(row, "PROMOTED");
        enqueueIndex(workspaceId, row.itemId(), "UPSERT", row.displayText(),
                payloadText(row.normalizedValueJson(), "slot", ""), firstNeighborhood(row));
        writeEvent(row.itemId(), "REVISION_REVIEWED", row.revisionId() + ":" + request.decision(),
                decisionPayload(request, null));
        return new MemoryRuntimeRevisionResponse(row.revisionId(), row.itemId(), "ACTIVE", revoked);
    }

    private MemoryRuntimeRevisionResponse reviewActive(
            String workspaceId,
            ReviewRow row,
            MemoryReviewDecisionRequest request
    ) {
        if ("ACCEPT".equals(request.decision())) {
            jdbcTemplate.update("""
                    update memory_item set status = 'ACTIVE', review_status = 'APPROVED',
                        lock_version = lock_version + 1, last_confirmed_at = current_timestamp,
                        updated_at = current_timestamp where id = ?
                    """, row.itemId());
            writeEvent(row.itemId(), "ACTIVE_REVIEW_ACCEPTED", row.revisionId(), decisionPayload(request, null));
            return new MemoryRuntimeRevisionResponse(row.revisionId(), row.itemId(), "ACTIVE", List.of());
        }
        if ("REVOKE".equals(request.decision())) {
            // 未被标记复核的生效记忆由用户主动停用，单独记录事件类型
            boolean flagged = "REVIEW_REQUIRED".equals(row.reviewStatus()) || "STALE".equals(row.itemStatus());
            revokeItem(workspaceId, row.itemId(), row.revisionId(),
                    flagged ? "ACTIVE_REVIEW_REVOKED" : "ACTIVE_USER_REVOKED",
                    decisionPayload(request, null));
            return new MemoryRuntimeRevisionResponse(row.revisionId(), row.itemId(), "REVOKED", List.of(row.itemId()));
        }
        throw new BusinessException(
                "MEMORY_RUNTIME_REVIEW_DECISION_INVALID",
                "Active Memory review 仅支持 ACCEPT 或 REVOKE");
    }

    private ReviewRow lockReview(String workspaceId, String revisionId, String decision) {
        List<ReviewRow> rows = jdbcTemplate.query("""
                select r.id, r.memory_item_id, r.status, r.display_text, r.provenance_type,
                       r.provenance_ref, r.normalized_value_json, i.status as item_status,
                       i.review_status, i.current_revision_id
                from memory_runtime_revision r
                join memory_item i on i.id = r.memory_item_id
                where r.workspace_id = ? and r.id = ?
                for update
                """, (rs, rowNum) -> new ReviewRow(
                rs.getString("id"),
                rs.getString("memory_item_id"),
                rs.getString("status"),
                rs.getString("display_text"),
                rs.getString("provenance_type"),
                rs.getString("provenance_ref"),
                rs.getString("normalized_value_json"),
                rs.getString("item_status"),
                rs.getString("review_status"),
                rs.getString("id").equals(rs.getString("current_revision_id"))
        ), workspaceId, revisionId);
        if (rows.isEmpty()) {
            throw new BusinessException("MEMORY_RUNTIME_REVISION_NOT_FOUND", "Memory revision 不存在");
        }
        ReviewRow row = rows.get(0);
        boolean proposed = "PROPOSED".equals(row.revisionStatus());
        boolean activeReview = "ACTIVE".equals(row.revisionStatus())
                && ("REVIEW_REQUIRED".equals(row.reviewStatus()) || "STALE".equals(row.itemStatus()));
        // 当前生效的版本随时可以由用户停用，其余审核动作仍只对待确认或需复核的版本开放
        boolean userRevoke = "ACTIVE".equals(row.revisionStatus()) && row.current() && "REVOKE".equals(decision);
        if (!proposed && !activeReview && !userRevoke) {
            throw new BusinessException("MEMORY_RUNTIME_REVISION_NOT_REVIEWABLE", "Memory revision 当前不需要审核");
        }
        jdbcTemplate.queryForObject("select id from memory_item where id = ? for update", String.class, row.itemId());
        return row;
    }

    private List<String> revokeConflictingItems(
            String workspaceId,
            ReviewRow proposal,
            MemoryReviewDecisionRequest request
    ) {
        String targetId = payloadText(proposal.normalizedValueJson(), "conflicting_item_id", "");
        if (!targetId.isBlank()) {
            ActiveRow target = lockActive(workspaceId, targetId, proposal.itemId());
            if (target != null && supersedeItem(workspaceId, target, proposal.revisionId(),
                    decisionPayload(request, proposal.revisionId()))) {
                return List.of(target.itemId());
            }
            return List.of();
        }
        CandidatePayload incoming = candidatePayload(proposal.normalizedValueJson(), proposal.displayText());
        List<ActiveRow> activeRows = jdbcTemplate.query("""
                select i.id, r.id as revision_id, r.display_text, r.normalized_value_json
                from memory_item i
                join memory_runtime_revision r on r.id = i.current_revision_id
                where i.workspace_id = ? and i.status = 'ACTIVE' and r.status = 'ACTIVE'
                  and i.id <> ?
                for update
                """, (rs, rowNum) -> new ActiveRow(
                rs.getString("id"),
                rs.getString("revision_id"),
                rs.getString("display_text"),
                rs.getString("normalized_value_json")
        ), workspaceId, proposal.itemId());
        List<String> revoked = new ArrayList<>();
        for (ActiveRow active : activeRows) {
            CandidatePayload existing = candidatePayload(active.normalizedValueJson(), active.displayText());
            Set<String> overlap = new LinkedHashSet<>(incoming.taskNeighborhoods());
            overlap.retainAll(existing.taskNeighborhoods());
            boolean legacyEquivalent = !incoming.candidateType().equals(existing.candidateType())
                    && statementMatcher.equivalent(incoming.statement(), existing.statement());
            if (!overlap.isEmpty() && (legacyEquivalent || slotConflict(incoming, existing))
                    && supersedeItem(workspaceId, active, proposal.revisionId(),
                    decisionPayload(request, proposal.revisionId()))) {
                revoked.add(active.itemId());
            }
        }
        return List.copyOf(revoked);
    }

    private ActiveRow lockActive(String workspaceId, String itemId, String exceptItemId) {
        List<ActiveRow> rows = jdbcTemplate.query("""
                select i.id, r.id as revision_id, r.display_text, r.normalized_value_json
                from memory_item i
                join memory_runtime_revision r on r.id = i.current_revision_id
                where i.workspace_id = ? and i.id = ? and i.id <> ?
                  and i.status = 'ACTIVE' and r.status = 'ACTIVE'
                for update
                """, (rs, rowNum) -> new ActiveRow(
                rs.getString("id"),
                rs.getString("revision_id"),
                rs.getString("display_text"),
                rs.getString("normalized_value_json")
        ), workspaceId, itemId, exceptItemId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean slotConflict(CandidatePayload incoming, CandidatePayload existing) {
        if (incoming.slot().isBlank() || !incoming.slot().equals(existing.slot())) {
            return false;
        }
        return !incoming.value().equals(existing.value()) || !incoming.polarity().equals(existing.polarity());
    }

    private boolean supersedeItem(
            String workspaceId,
            ActiveRow active,
            String replacedByRevisionId,
            String payloadJson
    ) {
        int updated = jdbcTemplate.update("""
                update memory_item set status = 'SUPERSEDED', review_status = 'SUPERSEDED',
                    semantic_slot_key = null, lock_version = lock_version + 1,
                    updated_at = current_timestamp
                where id = ? and status = 'ACTIVE'
                """, active.itemId());
        if (updated == 0) {
            return false;
        }
        jdbcTemplate.update("""
                update memory_runtime_revision
                set status = 'SUPERSEDED', valid_until = current_timestamp
                where id = ? and status = 'ACTIVE'
                """, active.revisionId());
        jdbcTemplate.update("""
                update memory_runtime_revision set supersedes_revision_id = ? where id = ?
                """, active.revisionId(), replacedByRevisionId);
        writeEvent(active.itemId(), "CONFLICT_SUPERSEDED", active.revisionId(), payloadJson);
        enqueueIndex(workspaceId, active.itemId(), "DELETE", active.displayText(), "", "");
        return true;
    }

    private void revokeItem(String workspaceId, String itemId, String revisionId, String eventType, String payloadJson) {
        jdbcTemplate.update("""
                update memory_item set status = 'DELETED', review_status = 'REJECTED',
                    semantic_slot_key = null, lock_version = lock_version + 1,
                    updated_at = current_timestamp where id = ?
                """, itemId);
        enqueueIndex(workspaceId, itemId, "DELETE", "", "", "");
        jdbcTemplate.update("""
                update memory_runtime_revision set status = 'REJECTED', valid_until = current_timestamp
                where id = ? and status = 'ACTIVE'
                """, revisionId);
        writeEvent(itemId, eventType, revisionId, payloadJson);
        replayRedactionService.redactDeletedMemoryRevision(revisionId);
    }

    private void updateCandidateStatus(ReviewRow row, String status) {
        if ("MEMORY_CANDIDATE".equals(row.provenanceType()) && row.provenanceRef() != null) {
            jdbcTemplate.update("""
                    update memory_candidate set review_status = ?, updated_at = current_timestamp
                    where id = ?
                    """, status, row.provenanceRef());
        }
    }

    private void writeEvent(String itemId, String eventType, String idempotencyKey) {
        writeEvent(itemId, eventType, idempotencyKey, null);
    }

    private void writeEvent(String itemId, String eventType, String idempotencyKey, String payloadJson) {
        Integer existing = jdbcTemplate.queryForObject("""
                select count(*) from memory_event
                where memory_item_id = ? and event_type = ? and idempotency_key = ?
                """, Integer.class, itemId, eventType, idempotencyKey);
        if (existing == null || existing == 0) {
            jdbcTemplate.update("""
                    insert into memory_event(id, memory_item_id, event_type, idempotency_key, payload_json)
                    values (?, ?, ?, ?, ?)
                    """, Ids.newId(), itemId, eventType, idempotencyKey, payloadJson);
        }
    }

    /** 审核事件记录决定与理由；冲突替换时同时记录替换它的新版本。 */
    private String decisionPayload(MemoryReviewDecisionRequest request, String replacedByRevisionId) {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("decision", request.decision());
        if (request.reason() != null) {
            payload.put("reason", request.reason());
        }
        if (replacedByRevisionId != null) {
            payload.put("replaced_by_revision_id", replacedByRevisionId);
        }
        return Json.write(objectMapper, payload);
    }

    private MemoryObjectResponse candidateResponse(MemoryCandidateService.CandidateRow candidate) {
        return new MemoryObjectResponse(
                candidate.candidateId(),
                candidate.workspaceId(),
                candidate.candidateType(),
                "WORKSPACE",
                candidate.normalizedStatement(),
                candidate.taskNeighborhoods(),
                "READY".equals(candidate.reviewStatus()) ? "ACTIVE" : "PROPOSED");
    }

    private String candidatePayload(MemoryCandidateService.CandidateRow candidate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("candidate_type", candidate.candidateType());
        payload.put("statement", candidate.normalizedStatement());
        payload.put("task_neighborhoods", candidate.taskNeighborhoods());
        payload.put("compile_hints", candidate.compileHints());
        payload.put("utility_score", candidate.marginalUtilityScore());
        payload.put("conflict_status", candidate.conflictStatus());
        payload.put("conflict_type", candidate.conflictType() == null ? "" : candidate.conflictType());
        payload.put("conflicting_item_id", candidate.conflictingItemId() == null ? "" : candidate.conflictingItemId());
        payload.put("conflicting_text", candidate.conflictingText() == null ? "" : candidate.conflictingText());
        payload.put("slot", candidate.slotKey() == null ? "" : candidate.slotKey());
        payload.put("slot_value", candidate.slotValue() == null ? "" : candidate.slotValue());
        payload.put("polarity", candidate.polarity() == null ? "" : candidate.polarity());
        payload.put("policy_version", candidate.policyVersion());
        payload.put("risk_score", candidate.riskScore());
        return Json.write(objectMapper, payload);
    }

    private CandidatePayload candidatePayload(String json, String displayText) {
        if (json == null || json.isBlank()) {
            return new CandidatePayload("UNKNOWN", displayText, List.of("COMMON"), "", "", "");
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            List<String> neighborhoods = root.path("task_neighborhoods").isArray()
                    ? objectMapper.treeToValue(root.path("task_neighborhoods"), new TypeReference<>() { })
                    : List.of("COMMON");
            return new CandidatePayload(
                    root.path("candidate_type").asText("UNKNOWN"),
                    root.path("statement").asText(displayText),
                    neighborhoods.isEmpty() ? List.of("COMMON") : neighborhoods,
                    root.path("slot").asText(""),
                    root.path("slot_value").asText(""),
                    root.path("polarity").asText(""));
        } catch (JsonProcessingException exception) {
            throw new BusinessException("MEMORY_CANONICAL_PAYLOAD_PARSE_FAILED", "Canonical Memory payload parse failed");
        }
    }

    private String payloadText(String json, String field, String fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readTree(json).path(field).asText(fallback);
        } catch (JsonProcessingException exception) {
            throw new BusinessException("MEMORY_CANONICAL_PAYLOAD_PARSE_FAILED", "Canonical Memory payload parse failed");
        }
    }

    private record ReviewRow(
            String revisionId,
            String itemId,
            String revisionStatus,
            String displayText,
            String provenanceType,
            String provenanceRef,
            String normalizedValueJson,
            String itemStatus,
            String reviewStatus,
            boolean current
    ) { }

    private record ActiveRow(
            String itemId,
            String revisionId,
            String displayText,
            String normalizedValueJson
    ) { }

    private void flagConflictingItem(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return;
        }
        jdbcTemplate.update("""
                update memory_item
                set review_status = 'REVIEW_REQUIRED', lock_version = lock_version + 1,
                    updated_at = current_timestamp
                where id = ? and status = 'ACTIVE' and review_status = 'APPROVED'
                """, itemId);
    }

    private void enqueueIndex(
            String workspaceId,
            String itemId,
            String operation,
            String displayText,
            String slotKey,
            String taskNeighborhood
    ) {
        indexOutbox.enqueue(workspaceId, itemId, operation, displayText, slotKey, taskNeighborhood);
    }

    private String displaySlot(String semanticSlot, String json) {
        String fromColumn = MemorySlots.publicSlot(semanticSlot);
        if (!"unknown".equals(fromColumn)) {
            return fromColumn;
        }
        return MemorySlots.publicSlot(payloadText(json, "slot", ""));
    }

    private MemoryConflictResponse conflictOf(String json) {
        return MemoryConflictResponse.from(
                payloadText(json, "conflict_type", ""),
                payloadText(json, "conflicting_item_id", ""),
                payloadText(json, "conflicting_text", ""),
                payloadText(json, "conflict_status", "NO_CONFLICT"));
    }

    private String firstNeighborhood(List<String> neighborhoods) {
        return neighborhoods == null || neighborhoods.isEmpty() ? "" : neighborhoods.get(0);
    }

    private String firstNeighborhood(ReviewRow row) {
        return firstNeighborhood(candidatePayload(row.normalizedValueJson(), row.displayText()).taskNeighborhoods());
    }

    private record CandidatePayload(
            String candidateType,
            String statement,
            List<String> taskNeighborhoods,
            String slot,
            String value,
            String polarity
    ) { }
}
