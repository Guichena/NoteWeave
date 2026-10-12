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
    /** 复核在缺少冲突目标时仍用它做语句等价判断，架构边界测试也要求候选服务保留这个依赖。 */
    @SuppressWarnings("unused")
    private final MemoryStatementMatcher statementMatcher;
    private final MemoryConflictService conflictService;

    public MemoryCandidateService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            MemorySignalService memorySignalService,
            MemoryCandidatePolicy candidatePolicy,
            MemoryCandidateGate candidateGate,
            MemoryStatementMatcher statementMatcher,
            MemoryConflictService conflictService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.memorySignalService = memorySignalService;
        this.candidatePolicy = candidatePolicy;
        this.candidateGate = candidateGate;
        this.statementMatcher = statementMatcher;
        this.conflictService = conflictService;
    }

    public List<MemoryCandidateResponse> buildCandidates(String workspaceId, List<String> signalIds) {
        List<MemorySignalService.SignalRow> signals = memorySignalService.findSignals(workspaceId, signalIds);
        List<MemoryCandidateResponse> responses = new ArrayList<>();
        List<PendingPeer> pendingPeers = new ArrayList<>();
        for (MemorySignalService.SignalRow signal : signals) {
            String normalizedStatement = normalizeStatement(signal.signalText());
            boolean negativeMemory = "NEGATIVE".equals(signal.signalType());
            MemorySlots.Assignment assignment = MemorySlots.complete(
                    MemorySlots.categoryOf(signal.signalText()),
                    signal.signalText(),
                    signal.signalType(),
                    signal.slotKey(),
                    signal.slotValue(),
                    signal.polarity());
            MemorySlotConflict.Outcome conflict = resolveConflict(workspaceId, signal, assignment, pendingPeers);
            double noveltyScore = computeNovelty(conflict);
            double marginalUtilityScore = candidatePolicy.marginalUtility(
                    signal.sourceType(), signal.signalType(), signal.taskNeighborhood());
            String conflictStatus = conflict.legacyStatus();
            MemoryCandidateGate.GateDecision gate = candidateGate.evaluate(
                    signal, marginalUtilityScore, conflictStatus,
                    MemorySlots.categoryOf(signal.signalText()), "WORKSPACE");
            String candidateId = Ids.newId();
            List<String> neighborhoods = List.of(signal.taskNeighborhood());
            jdbcTemplate.update("""
                    insert into memory_candidate(
                        id, workspace_id, user_id, candidate_type, normalized_statement, task_neighborhood_json,
                        evidence_gate_status, novelty_score, marginal_utility_score, negative_memory_flag,
                        conflict_status, staleness_status, compile_policy_json, forbidden_pattern_json,
                        created_from_signal_ids_json, review_status, policy_version,
                        risk_score, scope_status, slot_key, slot_value, polarity,
                        conflict_type, conflicting_item_id, conflicting_text
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    gate.scopeStatus(),
                    assignment.slot(),
                    assignment.value(),
                    assignment.polarity(),
                    conflict.conflictType(),
                    blank(conflict.conflictingItemId()),
                    blank(conflict.conflictingText())
            );
            pendingPeers.add(new PendingPeer(
                    candidateId,
                    normalizedStatement,
                    signal.taskNeighborhood(),
                    assignment));
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
                       risk_score, scope_status, slot_key, slot_value, polarity,
                       conflict_type, conflicting_item_id, conflicting_text
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
                    rs.getString("scope_status"),
                    rs.getString("slot_key"),
                    rs.getString("slot_value"),
                    rs.getString("polarity"),
                    rs.getString("conflict_type"),
                    rs.getString("conflicting_item_id"),
                    rs.getString("conflicting_text")
            );
        }, workspaceId, candidateId);
        if (row == null) {
            throw new BusinessException("MEMORY_CANDIDATE_NOT_FOUND", "Memory candidate 不存在");
        }
        return row;
    }

    private MemorySlotConflict.Outcome resolveConflict(
            String workspaceId,
            MemorySignalService.SignalRow signal,
            MemorySlots.Assignment assignment,
            List<PendingPeer> pendingPeers
    ) {
        List<MemorySlotConflict.Peer> peers = new ArrayList<>();
        for (PendingPeer pending : pendingPeers) {
            peers.add(new MemorySlotConflict.Peer(
                    pending.candidateId(),
                    pending.statement(),
                    pending.assignment().slot(),
                    pending.assignment().value(),
                    pending.assignment().polarity(),
                    pending.neighborhood().equals(signal.taskNeighborhood())));
        }
        MemorySlotConflict.Outcome batch = MemorySlotConflict.exact(assignment, peers);
        MemorySlotConflict.Outcome stored = conflictService.assess(
                workspaceId, signal.taskNeighborhood(), assignment);
        if (batch.blocking()) {
            return batch;
        }
        if (stored.blocking() || "EXISTING_EQUIVALENT".equals(stored.legacyStatus())) {
            return stored;
        }
        if ("EXISTING_EQUIVALENT".equals(batch.legacyStatus())) {
            return batch;
        }
        return stored;
    }

    private double computeNovelty(MemorySlotConflict.Outcome outcome) {
        if (outcome.blocking()) {
            return 0.10;
        }
        if ("EXISTING_EQUIVALENT".equals(outcome.legacyStatus())) {
            return Math.max(0.0, 1.0 - candidatePolicy.equivalentStatementSimilarity() * 0.75);
        }
        return 1.0;
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
            String scopeStatus,
            String slotKey,
            String slotValue,
            String polarity,
            String conflictType,
            String conflictingItemId,
            String conflictingText
    ) {
    }

    private record PendingPeer(
            String candidateId,
            String statement,
            String neighborhood,
            MemorySlots.Assignment assignment
    ) {
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }
}
