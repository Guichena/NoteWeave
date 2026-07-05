package com.noteweave.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MemoryCandidateService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MemorySignalService memorySignalService;

    public MemoryCandidateService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            MemorySignalService memorySignalService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.memorySignalService = memorySignalService;
    }

    public List<MemoryCandidateResponse> buildCandidates(String workspaceId, List<String> signalIds) {
        List<MemorySignalService.SignalRow> signals = memorySignalService.findSignals(workspaceId, signalIds);
        List<MemoryCandidateResponse> responses = new ArrayList<>();
        for (MemorySignalService.SignalRow signal : signals) {
            String normalizedStatement = normalizeStatement(signal.signalText());
            double noveltyScore = computeNovelty(workspaceId, normalizedStatement, signal.signalType(), signal.taskNeighborhood());
            double marginalUtilityScore = computeMarginalUtility(signal.sourceType(), signal.signalType(), signal.taskNeighborhood());
            boolean negativeMemory = "NEGATIVE".equals(signal.signalType());
            String evidenceGateStatus = signal.confidenceScore() >= 0.70 ? "PASS" : "NEEDS_REVIEW";
            String conflictStatus = detectConflict(workspaceId, normalizedStatement, negativeMemory, signal.taskNeighborhood());
            String reviewStatus = "PASS".equals(evidenceGateStatus)
                    && marginalUtilityScore >= 0.60
                    && !"CONFLICTING_ACTIVE_MEMORY".equals(conflictStatus)
                    ? "READY"
                    : "NEEDS_REVIEW";
            String candidateId = Ids.newId();
            List<String> neighborhoods = List.of(signal.taskNeighborhood());
            jdbcTemplate.update("""
                    insert into memory_candidate(
                        id, workspace_id, user_id, candidate_type, normalized_statement, task_neighborhood_json,
                        evidence_gate_status, novelty_score, marginal_utility_score, negative_memory_flag,
                        conflict_status, staleness_status, compile_policy_json, forbidden_pattern_json,
                        created_from_signal_ids_json, review_status
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?)
                    """,
                    candidateId,
                    workspaceId,
                    signal.userId(),
                    signal.signalType(),
                    normalizedStatement,
                    Json.write(objectMapper, neighborhoods),
                    evidenceGateStatus,
                    noveltyScore,
                    marginalUtilityScore,
                    negativeMemory,
                    conflictStatus,
                    Json.write(objectMapper, signal.compileHints()),
                    Json.write(objectMapper, signal.compileHints().forbiddenPatterns()),
                    Json.write(objectMapper, List.of(signal.signalId())),
                    reviewStatus
            );
            responses.add(new MemoryCandidateResponse(
                    candidateId,
                    workspaceId,
                    signal.signalType(),
                    normalizedStatement,
                    neighborhoods,
                    evidenceGateStatus,
                    noveltyScore,
                    marginalUtilityScore,
                    negativeMemory,
                    conflictStatus,
                    "ACTIVE",
                    reviewStatus
            ));
        }
        return responses;
    }

    CandidateRow findCandidate(String workspaceId, String candidateId) {
        CandidateRow row = jdbcTemplate.query("""
                select id, workspace_id, candidate_type, normalized_statement, task_neighborhood_json,
                       evidence_gate_status, novelty_score, marginal_utility_score, negative_memory_flag,
                       conflict_status, staleness_status, compile_policy_json, forbidden_pattern_json,
                       created_from_signal_ids_json, review_status
                from memory_candidate
                where workspace_id = ? and id = ?
                """, rs -> {
            if (!rs.next()) {
                return null;
            }
            return new CandidateRow(
                    rs.getString("id"),
                    rs.getString("workspace_id"),
                    rs.getString("candidate_type"),
                    rs.getString("normalized_statement"),
                    readStringList(rs.getString("task_neighborhood_json")),
                    rs.getString("evidence_gate_status"),
                    rs.getDouble("novelty_score"),
                    rs.getDouble("marginal_utility_score"),
                    rs.getBoolean("negative_memory_flag"),
                    rs.getString("conflict_status"),
                    rs.getString("staleness_status"),
                    readCompileHints(rs.getString("compile_policy_json")),
                    readStringList(rs.getString("forbidden_pattern_json")),
                    readStringList(rs.getString("created_from_signal_ids_json")),
                    rs.getString("review_status")
            );
        }, workspaceId, candidateId);
        if (row == null) {
            throw new BusinessException("MEMORY_CANDIDATE_NOT_FOUND", "Memory candidate 不存在");
        }
        return row;
    }

    private String detectConflict(String workspaceId, String statement, boolean negativeMemory, String taskNeighborhood) {
        List<ObjectRow> active = findMatchingActiveObjects(workspaceId, statement, taskNeighborhood);
        for (ObjectRow object : active) {
            boolean existingNegative = "NEGATIVE".equals(object.memoryType());
            if (existingNegative != negativeMemory) {
                return "CONFLICTING_ACTIVE_MEMORY";
            }
        }
        return active.isEmpty() ? "NO_CONFLICT" : "EXISTING_EQUIVALENT";
    }

    private double computeNovelty(String workspaceId, String statement, String signalType, String taskNeighborhood) {
        return findMatchingActiveObjects(workspaceId, statement, taskNeighborhood).isEmpty() ? 1.0 : 0.25;
    }

    private double computeMarginalUtility(String sourceType, String signalType, String taskNeighborhood) {
        double score = switch (sourceType) {
            case "USER_FEEDBACK", "PROJECT_DECISION" -> 0.90;
            case "ARTIFACT_FEEDBACK", "CONVERSATION_FEEDBACK" -> 0.72;
            default -> 0.40;
        };
        if ("DECISION".equals(signalType) || "NEGATIVE".equals(signalType)) {
            score += 0.05;
        }
        if ("COMMON".equals(taskNeighborhood)) {
            score += 0.03;
        }
        return Math.min(score, 0.99);
    }

    private List<ObjectRow> findMatchingActiveObjects(String workspaceId, String statement, String taskNeighborhood) {
        List<ObjectRow> rows = jdbcTemplate.query("""
                select id, memory_type, task_neighborhood_json, canonical_statement
                from memory_object
                where workspace_id = ? and status = 'ACTIVE'
                """, (rs, rowNum) -> new ObjectRow(
                rs.getString("id"),
                rs.getString("memory_type"),
                readStringList(rs.getString("task_neighborhood_json")),
                rs.getString("canonical_statement")
        ), workspaceId);
        List<ObjectRow> matches = new ArrayList<>();
        for (ObjectRow row : rows) {
            if (normalizeStatement(row.canonicalStatement()).equals(statement)
                    && row.taskNeighborhoods().contains(taskNeighborhood)) {
                matches.add(row);
            }
        }
        return matches;
    }

    private String normalizeStatement(String statement) {
        return statement == null ? "" : statement.replace("\r", "").replace('\n', ' ').trim();
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

    private MemorySignalService.MemoryCompileHints readCompileHints(String json) {
        if (json == null || json.isBlank()) {
            return new MemorySignalService.MemoryCompileHints(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new BusinessException("MEMORY_COMPILE_POLICY_PARSE_FAILED", "Memory compile policy 解析失败");
        }
    }

    record CandidateRow(
            String candidateId,
            String workspaceId,
            String candidateType,
            String normalizedStatement,
            List<String> taskNeighborhoods,
            String evidenceGateStatus,
            double noveltyScore,
            double marginalUtilityScore,
            boolean negativeMemory,
            String conflictStatus,
            String stalenessStatus,
            MemorySignalService.MemoryCompileHints compileHints,
            List<String> forbiddenPatterns,
            List<String> signalIds,
            String reviewStatus
    ) {
    }

    private record ObjectRow(String memoryObjectId, String memoryType, List<String> taskNeighborhoods, String canonicalStatement) {
    }
}
