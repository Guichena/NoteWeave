package com.noteweave.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MA3A server-side authority for canonical research-cell merges.
 *
 * <p>Workers may submit candidate facts, but they cannot directly mutate a
 * canonical cell. This service records every merge decision and performs the
 * cell update with all version/lease/fencing predicates in one SQL statement.</p>
 */
@Service
public class ResearchAgentCellMergeService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ResearchAgentCellMergeService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CandidateReceipt submitCandidate(CandidateCommand command) {
        validateCandidate(command);
        CandidateRow existing = findCandidateByIdempotency(command.researchRunId(), command.idempotencyKey());
        if (existing != null) {
            if (!existing.id().equals(command.candidateId())
                    || !existing.cellKey().equals(command.cellKey())
                    || existing.baseCellVersion() != command.baseCellVersion()) {
                throw new BusinessException(
                        "RESEARCH_AGENT_CANDIDATE_IDEMPOTENCY_CONFLICT",
                        "Candidate idempotency key is already bound to different content"
                );
            }
            return new CandidateReceipt(existing.id(), true);
        }
        try {
            jdbcTemplate.update("""
                    insert into research_agent_candidate(
                        id, research_run_id, task_id, execution_id, idempotency_key, cell_key,
                        base_cell_version, plan_revision, entity_set_version, lease_epoch, fencing_token,
                        candidate_value, evidence_ids_json, confidence_score
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    command.candidateId(),
                    command.researchRunId(),
                    command.taskId(),
                    command.executionId(),
                    command.idempotencyKey(),
                    command.cellKey(),
                    command.baseCellVersion(),
                    command.planRevision(),
                    command.entitySetVersion(),
                    command.leaseEpoch(),
                    command.fencingToken(),
                    command.candidateValue(),
                    Json.write(objectMapper, command.evidenceIds()),
                    BigDecimal.valueOf(command.confidence())
            );
            return new CandidateReceipt(command.candidateId(), false);
        } catch (DataIntegrityViolationException duplicate) {
            CandidateRow replay = findCandidateByIdempotency(command.researchRunId(), command.idempotencyKey());
            if (replay != null && replay.id().equals(command.candidateId())
                    && replay.cellKey().equals(command.cellKey())
                    && replay.baseCellVersion() == command.baseCellVersion()) {
                return new CandidateReceipt(replay.id(), true);
            }
            throw duplicate;
        }
    }

    @Transactional
    public MergeResult mergeCandidate(MergeCommand command) {
        MergeResult replay = findMerge(command.researchRunId(), command.mergeId());
        if (replay != null) {
            return new MergeResult(
                    "IDEMPOTENT_REPLAY",
                    "MERGE_ALREADY_APPLIED",
                    replay.expectedCellVersion(),
                    replay.resultCellVersion()
            );
        }
        CandidateRow candidate = findCandidate(command.candidateId(), command.researchRunId());
        if (candidate == null) {
            throw new BusinessException("RESEARCH_AGENT_CANDIDATE_NOT_FOUND", "Research candidate does not exist");
        }
        CellRow cell = findCell(command.researchRunId(), candidate.cellKey());
        if (cell == null) {
            return appendRejected(command, candidate, candidate.baseCellVersion(), 0, "CELL_NOT_FOUND");
        }
        if (!"SUPPORTS".equals(command.verdict())) {
            return appendRejected(command, candidate, candidate.baseCellVersion(), cell.cellVersion(), "VERDICT_NOT_SUPPORTS");
        }
        if (!hasExactEvidenceBinding(candidate)) {
            return appendRejected(command, candidate, candidate.baseCellVersion(), cell.cellVersion(), "EVIDENCE_BINDING_INVALID");
        }
        String staleReason = validateMergeBinding(cell, candidate);
        if (staleReason != null) {
            return appendRejected(command, candidate, candidate.baseCellVersion(), cell.cellVersion(), staleReason);
        }

        int updated = jdbcTemplate.update("""
                update research_cell
                set candidate_value = ?, cell_status = 'VERIFIED', confidence_score = ?,
                    evidence_refs_json = ?, last_verifier_decision = 'MERGE_GATE:VERIFIED_AND_VERSION_MATCHED',
                    cell_version = cell_version + 1, last_merge_id = ?, updated_at = current_timestamp
                where id = ? and research_run_id = ? and cell_key = ?
                  and cell_status <> 'FROZEN'
                  and cell_version = ? and plan_revision = ? and entity_set_version = ?
                  and active_task_id = ? and lease_epoch = ? and fencing_token = ?
                """,
                candidate.candidateValue(),
                candidate.confidenceScore(),
                Json.write(objectMapper, candidate.evidenceIds()),
                command.mergeId(),
                cell.id(),
                command.researchRunId(),
                candidate.cellKey(),
                candidate.baseCellVersion(),
                candidate.planRevision(),
                candidate.entitySetVersion(),
                candidate.taskId(),
                candidate.leaseEpoch(),
                candidate.fencingToken()
        );
        if (updated == 1) {
            return appendAccepted(command, candidate, candidate.baseCellVersion(), candidate.baseCellVersion() + 1);
        }

        CellRow racedCell = findCell(command.researchRunId(), candidate.cellKey());
        String reason = racedCell == null ? "CELL_NOT_FOUND" : nonNullReason(validateMergeBinding(racedCell, candidate), "CAS_UPDATE_REJECTED");
        int resultVersion = racedCell == null ? 0 : racedCell.cellVersion();
        return appendRejected(command, candidate, candidate.baseCellVersion(), resultVersion, reason);
    }

    private MergeResult appendAccepted(MergeCommand command, CandidateRow candidate, int expected, int result) {
        return appendMerge(command, candidate, expected, result, "ACCEPTED", "VERIFIED_AND_VERSION_MATCHED", candidate.evidenceIds());
    }

    private MergeResult appendRejected(MergeCommand command, CandidateRow candidate, int expected, int result, String reason) {
        return appendMerge(command, candidate, expected, result, "REJECTED", reason, List.of());
    }

    private MergeResult appendMerge(
            MergeCommand command,
            CandidateRow candidate,
            int expected,
            int result,
            String decision,
            String reason,
            List<String> acceptedEvidenceIds
    ) {
        try {
            jdbcTemplate.update("""
                    insert into research_cell_merge(
                        id, research_run_id, candidate_id, merge_key, cell_key,
                        expected_cell_version, result_cell_version, verdict, decision,
                        reason_code, accepted_evidence_ids_json
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    Ids.newId(),
                    command.researchRunId(),
                    candidate.id(),
                    command.mergeId(),
                    candidate.cellKey(),
                    expected,
                    result,
                    command.verdict(),
                    decision,
                    reason,
                    Json.write(objectMapper, acceptedEvidenceIds)
            );
            return new MergeResult(decision, reason, expected, result);
        } catch (DataIntegrityViolationException duplicate) {
            MergeResult existing = findMerge(command.researchRunId(), command.mergeId());
            if (existing != null) {
                return new MergeResult("IDEMPOTENT_REPLAY", "MERGE_ALREADY_APPLIED", existing.expectedCellVersion(), existing.resultCellVersion());
            }
            throw duplicate;
        }
    }

    private String validateMergeBinding(CellRow cell, CandidateRow candidate) {
        if ("FROZEN".equals(cell.cellStatus())) return "CELL_FROZEN";
        if (cell.cellVersion() != candidate.baseCellVersion()) return "STALE_CELL_VERSION";
        if (cell.planRevision() != candidate.planRevision()) return "STALE_PLAN_REVISION";
        if (cell.entitySetVersion() != candidate.entitySetVersion()) return "STALE_ENTITY_SET_VERSION";
        if (!candidate.taskId().equals(cell.activeTaskId())) return "TARGET_TASK_MISMATCH";
        if (cell.leaseEpoch() != candidate.leaseEpoch()) return "LEASE_EPOCH_MISMATCH";
        if (cell.fencingToken() != candidate.fencingToken()) return "FENCING_TOKEN_MISMATCH";
        return null;
    }

    private boolean hasExactEvidenceBinding(CandidateRow candidate) {
        if (candidate.evidenceIds().isEmpty()) return false;
        String placeholders = String.join(",", java.util.Collections.nCopies(candidate.evidenceIds().size(), "?"));
        List<Object> parameters = new java.util.ArrayList<>();
        parameters.add(candidate.researchRunId());
        parameters.addAll(candidate.evidenceIds());
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from source_evidence where research_run_id = ? and evidence_key in (" + placeholders + ")",
                Integer.class,
                parameters.toArray()
        );
        return count != null && count == candidate.evidenceIds().size();
    }

    private CandidateRow findCandidate(String candidateId, String runId) {
        return jdbcTemplate.query("""
                select id, research_run_id, task_id, cell_key, base_cell_version, plan_revision,
                       entity_set_version, lease_epoch, fencing_token, candidate_value,
                       evidence_ids_json, confidence_score
                from research_agent_candidate where id = ? and research_run_id = ?
                """, rs -> rs.next() ? mapCandidate(rs) : null, candidateId, runId);
    }

    private CandidateRow findCandidateByIdempotency(String runId, String idempotencyKey) {
        return jdbcTemplate.query("""
                select id, research_run_id, task_id, cell_key, base_cell_version, plan_revision,
                       entity_set_version, lease_epoch, fencing_token, candidate_value,
                       evidence_ids_json, confidence_score
                from research_agent_candidate where research_run_id = ? and idempotency_key = ?
                """, rs -> rs.next() ? mapCandidate(rs) : null, runId, idempotencyKey);
    }

    private CandidateRow mapCandidate(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CandidateRow(
                rs.getString("id"), rs.getString("research_run_id"), rs.getString("task_id"),
                rs.getString("cell_key"), rs.getInt("base_cell_version"), rs.getInt("plan_revision"),
                rs.getInt("entity_set_version"), rs.getInt("lease_epoch"), rs.getLong("fencing_token"),
                rs.getString("candidate_value"), readStringList(rs.getString("evidence_ids_json")),
                rs.getBigDecimal("confidence_score") == null ? 0.0 : rs.getBigDecimal("confidence_score").doubleValue()
        );
    }

    private CellRow findCell(String runId, String cellKey) {
        return jdbcTemplate.query("""
                select id, cell_status, cell_version, plan_revision, entity_set_version,
                       active_task_id, lease_epoch, fencing_token
                from research_cell where research_run_id = ? and cell_key = ?
                """, rs -> rs.next()
                ? new CellRow(rs.getString("id"), rs.getString("cell_status"), rs.getInt("cell_version"),
                rs.getInt("plan_revision"), rs.getInt("entity_set_version"), rs.getString("active_task_id"),
                rs.getInt("lease_epoch"), rs.getLong("fencing_token")) : null, runId, cellKey);
    }

    private MergeResult findMerge(String runId, String mergeId) {
        return jdbcTemplate.query("""
                select decision, reason_code, expected_cell_version, result_cell_version
                from research_cell_merge where research_run_id = ? and merge_key = ?
                """, rs -> rs.next()
                ? new MergeResult(rs.getString("decision"), rs.getString("reason_code"),
                rs.getInt("expected_cell_version"), rs.getInt("result_cell_version")) : null, runId, mergeId);
    }

    private List<String> readStringList(String rawJson) {
        try {
            return objectMapper.readValue(rawJson, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException exception) {
            throw new BusinessException("RESEARCH_AGENT_CANDIDATE_EVIDENCE_PARSE_FAILED", "Candidate evidence payload is invalid");
        }
    }

    private void validateCandidate(CandidateCommand command) {
        if (isBlank(command.candidateId()) || isBlank(command.researchRunId()) || isBlank(command.taskId())
                || isBlank(command.executionId()) || isBlank(command.idempotencyKey()) || isBlank(command.cellKey())
                || isBlank(command.candidateValue()) || command.evidenceIds() == null || command.evidenceIds().isEmpty()
                || command.baseCellVersion() < 0 || command.planRevision() < 0 || command.entitySetVersion() < 0
                || command.leaseEpoch() < 1 || command.fencingToken() < 1
                || command.confidence() < 0.0 || command.confidence() > 1.0) {
            throw new BusinessException("RESEARCH_AGENT_CANDIDATE_INVALID", "Candidate contract is invalid");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String nonNullReason(String value, String fallback) {
        return value == null ? fallback : value;
    }

    public record CandidateCommand(
            String candidateId, String researchRunId, String taskId, String executionId, String idempotencyKey,
            String cellKey, int baseCellVersion, int planRevision, int entitySetVersion, int leaseEpoch,
            long fencingToken, String candidateValue, List<String> evidenceIds, double confidence
    ) { }

    public record CandidateReceipt(String candidateId, boolean idempotentReplay) { }

    public record MergeCommand(String researchRunId, String candidateId, String mergeId, String verdict) { }

    public record MergeResult(String decision, String reasonCode, int expectedCellVersion, int resultCellVersion) { }

    private record CandidateRow(
            String id, String researchRunId, String taskId, String cellKey, int baseCellVersion,
            int planRevision, int entitySetVersion, int leaseEpoch, long fencingToken, String candidateValue,
            List<String> evidenceIds, double confidenceScore
    ) { }

    private record CellRow(
            String id, String cellStatus, int cellVersion, int planRevision, int entitySetVersion,
            String activeTaskId, int leaseEpoch, long fencingToken
    ) { }
}
