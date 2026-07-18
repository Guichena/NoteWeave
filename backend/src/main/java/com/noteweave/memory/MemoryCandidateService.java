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
    private final MemoryCandidatePolicy candidatePolicy;
    private final MemoryCandidateGate candidateGate;
    private final MemoryStatementMatcher statementMatcher;

    public MemoryCandidateService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            MemorySignalService memorySignalService,
            MemoryCandidatePolicy candidatePolicy,
            MemoryCandidateGate candidateGate,
            MemoryStatementMatcher statementMatcher
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.memorySignalService = memorySignalService;
        this.candidatePolicy = candidatePolicy;
        this.candidateGate = candidateGate;
        this.statementMatcher = statementMatcher;
    }

    public List<MemoryCandidateResponse> buildCandidates(String workspaceId, List<String> signalIds) {
        List<MemorySignalService.SignalRow> signals = memorySignalService.findSignals(workspaceId, signalIds);
        List<MemoryCandidateResponse> responses = new ArrayList<>();
        for (MemorySignalService.SignalRow signal : signals) {
            String normalizedStatement = normalizeStatement(signal.signalText());
            boolean negativeMemory = "NEGATIVE".equals(signal.signalType());
            List<ObjectMatch> activeMatches = findMatchingActiveObjects(
                    workspaceId, normalizedStatement, signal.taskNeighborhood());
            double noveltyScore = computeNovelty(activeMatches);
            double marginalUtilityScore = candidatePolicy.marginalUtility(
                    signal.sourceType(), signal.signalType(), signal.taskNeighborhood());
            String conflictStatus = detectConflict(activeMatches, negativeMemory);
            MemoryCandidateGate.GateDecision gate = candidateGate.evaluate(
                    signal, marginalUtilityScore, conflictStatus);
            String candidateId = Ids.newId();
            List<String> neighborhoods = List.of(signal.taskNeighborhood());
            jdbcTemplate.update("""
                    insert into memory_candidate(
                        id, workspace_id, user_id, candidate_type, normalized_statement, task_neighborhood_json,
                        evidence_gate_status, novelty_score, marginal_utility_score, negative_memory_flag,
                        conflict_status, staleness_status, compile_policy_json, forbidden_pattern_json,
                        created_from_signal_ids_json, review_status, policy_version,
                        risk_score, scope_status
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?)
                    """,
                    candidateId,
                    workspaceId,
                    signal.userId(),
                    signal.signalType(),
                    normalizedStatement,
                    Json.write(objectMapper, neighborhoods),
                    gate.evidenceGateStatus(),
                    noveltyScore,
                    marginalUtilityScore,
                    negativeMemory,
                    conflictStatus,
                    Json.write(objectMapper, signal.compileHints()),
                    Json.write(objectMapper, signal.compileHints().forbiddenPatterns()),
                    Json.write(objectMapper, List.of(signal.signalId())),
                    gate.reviewStatus(),
                    gate.policyVersion(),
                    gate.riskScore(),
                    gate.scopeStatus()
            );
            responses.add(new MemoryCandidateResponse(
                    candidateId,
                    workspaceId,
                    signal.signalType(),
                    normalizedStatement,
                    neighborhoods,
                    gate.evidenceGateStatus(),
                    noveltyScore,
                    marginalUtilityScore,
                    negativeMemory,
                    conflictStatus,
                    "ACTIVE",
                    gate.reviewStatus(),
                    gate.riskScore(),
                    gate.scopeStatus(),
                    gate.policyVersion()
            ));
        }
        return responses;
    }

    CandidateRow findCandidate(String workspaceId, String candidateId) {
        CandidateRow row = jdbcTemplate.query("""
                select id, workspace_id, candidate_type, normalized_statement, task_neighborhood_json,
                       evidence_gate_status, novelty_score, marginal_utility_score, negative_memory_flag,
                       conflict_status, staleness_status, compile_policy_json, forbidden_pattern_json,
                       created_from_signal_ids_json, review_status, policy_version,
                       risk_score, scope_status
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
                    rs.getString("review_status"),
                    rs.getString("policy_version"),
                    rs.getDouble("risk_score"),
                    rs.getString("scope_status")
            );
        }, workspaceId, candidateId);
        if (row == null) {
            throw new BusinessException("MEMORY_CANDIDATE_NOT_FOUND", "Memory candidate 不存在");
        }
        return row;
    }

    private String detectConflict(List<ObjectMatch> active, boolean negativeMemory) {
        for (ObjectMatch match : active) {
            boolean existingNegative = "NEGATIVE".equals(match.object().memoryType());
            if (existingNegative != negativeMemory) {
                return "CONFLICTING_ACTIVE_MEMORY";
            }
        }
        return active.isEmpty() ? "NO_CONFLICT" : "EXISTING_EQUIVALENT";
    }

    private double computeNovelty(List<ObjectMatch> matches) {
        double maximumSimilarity = matches.stream()
                .mapToDouble(ObjectMatch::similarity)
                .max()
                .orElse(0.0);
        return maximumSimilarity == 0.0
                ? 1.0
                : Math.max(0.0, 1.0 - maximumSimilarity * 0.75);
    }

    private List<ObjectMatch> findMatchingActiveObjects(
            String workspaceId,
            String statement,
            String taskNeighborhood
    ) {
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
        List<ObjectMatch> matches = new ArrayList<>();
        for (ObjectRow row : rows) {
            double similarity = statementMatcher.similarity(
                    row.canonicalStatement(), statement);
            if (similarity >= candidatePolicy.equivalentStatementSimilarity()
                    && row.taskNeighborhoods().contains(taskNeighborhood)) {
                matches.add(new ObjectMatch(row, similarity));
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
            String reviewStatus,
            String policyVersion,
            double riskScore,
            String scopeStatus
    ) {
    }

    private record ObjectRow(String memoryObjectId, String memoryType, List<String> taskNeighborhoods, String canonicalStatement) {
    }

    private record ObjectMatch(ObjectRow object, double similarity) {
    }
}
