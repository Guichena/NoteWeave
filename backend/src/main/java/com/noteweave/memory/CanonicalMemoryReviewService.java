package com.noteweave.memory;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.security.CurrentUserProvider;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CanonicalMemoryReviewService {
    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserProvider currentUserProvider;
    private final WorkspaceAccessGuard workspaceAccessGuard;

    public CanonicalMemoryReviewService(JdbcTemplate jdbcTemplate, CurrentUserProvider currentUserProvider,
                                        WorkspaceAccessGuard workspaceAccessGuard) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserProvider = currentUserProvider;
        this.workspaceAccessGuard = workspaceAccessGuard;
    }

    public void projectProposal(MemoryCandidateService.CandidateRow candidate) {
        if (!"NEEDS_REVIEW".equals(candidate.reviewStatus())) return;
        Integer existing = jdbcTemplate.queryForObject("select count(*) from memory_runtime_revision where id = ?",
                Integer.class, candidate.candidateId());
        if (existing != null && existing > 0) return;
        String actor = currentUserProvider.requireUserId();
        jdbcTemplate.update("""
                insert into memory_item(id, workspace_id, owner_user_id, memory_scope, scope_ref_key, slot_key,
                    slot_schema_version, status, lock_version)
                values (?, ?, ?, 'WORKSPACE', ?, ?, 'candidate-v1', 'EMPTY', 0)
                """, candidate.candidateId(), candidate.workspaceId(), actor, candidate.workspaceId(),
                "candidate:" + candidate.candidateId());
        jdbcTemplate.update("""
                insert into memory_runtime_revision(id, memory_item_id, workspace_id, version_no, status,
                    confidence, display_text, provenance_type, provenance_ref, observation_id, content_hash)
                values (?, ?, ?, 1, 'PROPOSED', ?, ?, 'MEMORY_CANDIDATE', ?, ?, ?)
                """, candidate.candidateId(), candidate.candidateId(), candidate.workspaceId(),
                candidate.marginalUtilityScore(), candidate.normalizedStatement(), candidate.candidateId(),
                candidate.candidateId(), candidate.candidateId());
    }

    public List<MemoryRuntimeReviewItemResponse> list(String workspaceId) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.MEMORY_REVIEW);
        return jdbcTemplate.query("""
                select r.id, r.memory_item_id, r.status, r.display_text, r.provenance_ref
                from memory_runtime_revision r join memory_item i on i.id = r.memory_item_id
                where r.workspace_id = ? and r.status = 'PROPOSED' and i.status <> 'DELETED'
                order by r.created_at, r.id
                """, (rs, n) -> new MemoryRuntimeReviewItemResponse(rs.getString("id"),
                rs.getString("memory_item_id"), rs.getString("status"), rs.getString("display_text"),
                rs.getString("provenance_ref")), workspaceId);
    }

    @Transactional
    public MemoryRuntimeRevisionResponse review(String workspaceId, String revisionId, MemoryReviewDecisionRequest request) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.MEMORY_REVIEW);
        Revision row = jdbcTemplate.query("""
                select r.id, r.memory_item_id, r.status from memory_runtime_revision r
                where r.workspace_id = ? and r.id = ? for update
                """, rs -> rs.next() ? new Revision(rs.getString("id"), rs.getString("memory_item_id"),
                rs.getString("status")) : null, workspaceId, revisionId);
        if (row == null) throw new BusinessException("MEMORY_RUNTIME_REVISION_NOT_FOUND", "Memory revision 不存在");
        if (!"PROPOSED".equals(row.status())) throw new BusinessException("MEMORY_RUNTIME_REVISION_NOT_PROPOSED", "Memory revision 不处于待审核状态");
        jdbcTemplate.queryForObject("select id from memory_item where id = ? for update", String.class, row.itemId());
        if ("REJECT".equals(request.decision())) {
            jdbcTemplate.update("update memory_runtime_revision set status = 'REJECTED' where id = ?", revisionId);
            return new MemoryRuntimeRevisionResponse(revisionId, row.itemId(), "REJECTED");
        }
        if (!"ACCEPT".equals(request.decision())) throw new BusinessException("MEMORY_RUNTIME_REVIEW_DECISION_INVALID", "仅支持 ACCEPT 或 REJECT");
        jdbcTemplate.update("""
                update memory_runtime_revision set status = 'SUPERSEDED', valid_until = current_timestamp
                where memory_item_id = ? and status = 'ACTIVE'
                """, row.itemId());
        jdbcTemplate.update("update memory_runtime_revision set status = 'ACTIVE', valid_from = current_timestamp where id = ?", revisionId);
        jdbcTemplate.update("""
                update memory_item set current_revision_id = ?, status = 'ACTIVE',
                lock_version = lock_version + 1, last_confirmed_at = current_timestamp, updated_at = current_timestamp where id = ?
                """,
                revisionId, row.itemId());
        jdbcTemplate.update("insert into memory_event(id, memory_item_id, event_type, idempotency_key) values (?, ?, 'REVISION_REVIEWED', ?)",
                Ids.newId(), row.itemId(), revisionId + ":ACCEPT");
        return new MemoryRuntimeRevisionResponse(revisionId, row.itemId(), "ACTIVE");
    }
    private record Revision(String id, String itemId, String status) {}
}
