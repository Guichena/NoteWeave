package com.noteweave.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.noteweave.common.BusinessException;
import com.noteweave.security.CurrentUserProvider;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemoryPromotionService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MemoryCandidateService memoryCandidateService;
    private final CurrentUserProvider currentUserProvider;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final MemoryStatementMatcher statementMatcher;
    private final MemoryVersionService memoryVersionService;
    private final CanonicalMemoryReviewService canonicalMemoryReviewService;

    public MemoryPromotionService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            MemoryCandidateService memoryCandidateService,
            CurrentUserProvider currentUserProvider,
            WorkspaceAccessGuard workspaceAccessGuard,
            MemoryStatementMatcher statementMatcher,
            MemoryVersionService memoryVersionService,
            CanonicalMemoryReviewService canonicalMemoryReviewService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.memoryCandidateService = memoryCandidateService;
        this.currentUserProvider = currentUserProvider;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.statementMatcher = statementMatcher;
        this.memoryVersionService = memoryVersionService;
        this.canonicalMemoryReviewService = canonicalMemoryReviewService;
    }

    @Transactional
    public MemoryPromotionResponse promoteSignals(String workspaceId, List<String> signalIds) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.MEMORY_REVIEW);
        String userId = currentUserProvider.requireUserId();
        List<MemoryCandidateResponse> builtCandidates = memoryCandidateService.buildCandidates(workspaceId, signalIds);
        List<MemoryObjectResponse> objects = new ArrayList<>();
        for (MemoryCandidateResponse response : builtCandidates) {
            MemoryCandidateService.CandidateRow candidate = memoryCandidateService.findCandidate(workspaceId, response.candidateId());
            if (!"READY".equals(candidate.reviewStatus())) {
                canonicalMemoryReviewService.projectProposal(candidate);
                continue;
            }
            objects.add(promoteReadyCandidate(workspaceId, userId, candidate));
        }
        return new MemoryPromotionResponse(builtCandidates, objects);
    }

    @Transactional
    public MemoryObjectResponse promoteCandidate(String workspaceId, String candidateId) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.MEMORY_REVIEW);
        MemoryCandidateService.CandidateRow candidate =
                memoryCandidateService.findCandidate(workspaceId, candidateId);
        if (!"READY".equals(candidate.reviewStatus())) {
            throw new BusinessException(
                    "MEMORY_CANDIDATE_NOT_READY",
                    "Memory candidate 尚未通过 review"
            );
        }
        return promoteReadyCandidate(
                workspaceId, currentUserProvider.requireUserId(), candidate);
    }

    private MemoryObjectResponse promoteReadyCandidate(
            String workspaceId,
            String userId,
            MemoryCandidateService.CandidateRow candidate
    ) {
        MemoryObjectResponse existing = findEquivalentActiveObject(workspaceId, candidate);
        if (existing != null) {
            jdbcTemplate.update("update memory_candidate set review_status = 'MERGED_EXISTING', updated_at = current_timestamp where id = ?",
                    candidate.candidateId());
            return existing;
        }
        String memoryObjectId = Ids.newId();
        String memoryType = candidate.candidateType();
        String ledgerJson = Json.write(objectMapper, new MemoryLedger(
                candidate.signalIds(),
                candidate.evidenceGateStatus(),
                candidate.noveltyScore(),
                candidate.marginalUtilityScore(),
                candidate.candidateId(),
                candidate.policyVersion(),
                candidate.riskScore(),
                candidate.scopeStatus()
        ));
        jdbcTemplate.update("""
                insert into memory_object(
                    id, workspace_id, user_id, memory_type, memory_scope, canonical_statement,
                    task_neighborhood_json, compile_policy_json, forbidden_pattern_json, ledger_json,
                    status, utility_score, review_status, outcome_policy_version
                ) values (?, ?, ?, ?, 'WORKSPACE', ?, ?, ?, ?, ?, 'ACTIVE', ?, 'APPROVED', ?)
                """,
                memoryObjectId,
                workspaceId,
                userId,
                memoryType,
                candidate.normalizedStatement(),
                Json.write(objectMapper, candidate.taskNeighborhoods()),
                Json.write(objectMapper, candidate.compileHints()),
                Json.write(objectMapper, candidate.forbiddenPatterns()),
                ledgerJson,
                candidate.marginalUtilityScore(),
                MemoryOutcomePolicy.VERSION
        );
        memoryVersionService.createInitialVersion(
                new MemoryVersionService.InitialVersionCommand(
                        workspaceId,
                        memoryObjectId,
                        candidate.normalizedStatement(),
                        candidate.taskNeighborhoods(),
                        candidate.compileHints(),
                        candidate.forbiddenPatterns(),
                        candidate.candidateId(),
                        candidate.policyVersion(),
                        candidate.riskScore(),
                        candidate.scopeStatus()
                ));
        jdbcTemplate.update("update memory_candidate set review_status = 'PROMOTED', updated_at = current_timestamp where id = ?",
                candidate.candidateId());
        return new MemoryObjectResponse(
                memoryObjectId,
                workspaceId,
                memoryType,
                "WORKSPACE",
                candidate.normalizedStatement(),
                candidate.taskNeighborhoods(),
                "ACTIVE"
        );
    }

    private MemoryObjectResponse findEquivalentActiveObject(String workspaceId, MemoryCandidateService.CandidateRow candidate) {
        List<MemoryObjectResponse> existing = jdbcTemplate.query("""
                select id, workspace_id, memory_type, memory_scope, canonical_statement, task_neighborhood_json, status
                from memory_object
                where workspace_id = ? and status = 'ACTIVE'
                """, (rs, rowNum) -> new MemoryObjectResponse(
                rs.getString("id"),
                rs.getString("workspace_id"),
                rs.getString("memory_type"),
                rs.getString("memory_scope"),
                rs.getString("canonical_statement"),
                readStringList(rs.getString("task_neighborhood_json")),
                rs.getString("status")
        ), workspaceId);
        for (MemoryObjectResponse response : existing) {
            Set<String> left = new LinkedHashSet<>(response.taskNeighborhoods());
            left.retainAll(candidate.taskNeighborhoods());
            if (!left.isEmpty()
                    && response.memoryType().equals(candidate.candidateType())
                    && statementMatcher.equivalent(
                    response.canonicalStatement(), candidate.normalizedStatement())) {
                return response;
            }
        }
        return null;
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private record MemoryLedger(
            List<String> sourceSignalIds,
            String evidenceGateStatus,
            double noveltyScore,
            double marginalUtilityScore,
            String promotedFromCandidateId,
            String policyVersion,
            double riskScore,
            String scopeStatus
    ) {
    }
}
