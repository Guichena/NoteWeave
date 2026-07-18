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
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MemorySignalService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CurrentUserProvider currentUserProvider;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final MemoryCandidatePolicy candidatePolicy;

    public MemorySignalService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            CurrentUserProvider currentUserProvider,
            WorkspaceAccessGuard workspaceAccessGuard,
            MemoryCandidatePolicy candidatePolicy
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.currentUserProvider = currentUserProvider;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.candidatePolicy = candidatePolicy;
    }

    public MemorySignalResponse createSignal(String workspaceId, CreateMemorySignalRequest request) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.ANSWER_RUN);
        String userId = currentUserProvider.requireUserId();
        MemoryCompileHints hints = new MemoryCompileHints(
                normalizeList(request.styleConstraints()),
                normalizeList(request.structureConstraints()),
                normalizeList(request.terminologyPolicy()),
                normalizeList(request.forbiddenPatterns()),
                normalizeList(request.interactionPolicy()),
                normalizeList(request.reviewChecklist())
        );
        String signalId = Ids.newId();
        String signalType = normalizeToken(request.signalType());
        String sourceType = normalizeToken(request.sourceType());
        String taskNeighborhood = normalizeToken(request.taskNeighborhood());
        double confidenceScore = candidatePolicy.confidenceForSource(sourceType);
        jdbcTemplate.update("""
                insert into memory_signal(
                    id, workspace_id, user_id, source_type, source_id, signal_type, signal_text,
                    task_neighborhood, compile_hints_json, confidence_score, policy_version
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                signalId,
                workspaceId,
                userId,
                sourceType,
                blankToNull(request.sourceId()),
                signalType,
                request.signalText().trim(),
                taskNeighborhood,
                Json.write(objectMapper, hints),
                confidenceScore,
                candidatePolicy.version()
        );
        return new MemorySignalResponse(
                signalId,
                workspaceId,
                signalType,
                sourceType,
                blankToNull(request.sourceId()),
                request.signalText().trim(),
                taskNeighborhood,
                confidenceScore,
                candidatePolicy.version()
        );
    }

    List<SignalRow> findSignals(String workspaceId, List<String> signalIds) {
        if (signalIds == null || signalIds.isEmpty()) {
            return List.of();
        }
        List<SignalRow> rows = new ArrayList<>();
        for (String signalId : signalIds) {
            SignalRow row = jdbcTemplate.query("""
                    select id, workspace_id, user_id, source_type, source_id, signal_type, signal_text,
                           task_neighborhood, compile_hints_json, confidence_score, policy_version
                    from memory_signal
                    where workspace_id = ? and id = ?
                    """, rs -> {
                if (!rs.next()) {
                    return null;
                }
                return new SignalRow(
                        rs.getString("id"),
                        rs.getString("workspace_id"),
                        rs.getString("user_id"),
                        rs.getString("source_type"),
                        rs.getString("source_id"),
                        rs.getString("signal_type"),
                        rs.getString("signal_text"),
                        rs.getString("task_neighborhood"),
                        readCompileHints(rs.getString("compile_hints_json")),
                        rs.getDouble("confidence_score"),
                        rs.getString("policy_version")
                );
            }, workspaceId, signalId);
            if (row == null) {
                throw new BusinessException("MEMORY_SIGNAL_NOT_FOUND", "Memory signal 不存在");
            }
            rows.add(row);
        }
        return rows;
    }

    private MemoryCompileHints readCompileHints(String json) {
        if (json == null || json.isBlank()) {
            return new MemoryCompileHints(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new BusinessException("MEMORY_SIGNAL_PARSE_FAILED", "Memory signal 配置解析失败");
        }
    }

    static List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String trimmed = value.trim();
            if (!normalized.contains(trimmed)) {
                normalized.add(trimmed);
            }
        }
        return List.copyOf(normalized);
    }

    static String normalizeToken(String value) {
        return value == null ? "" : value.trim().toUpperCase().replace('-', '_').replace(' ', '_');
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    record MemoryCompileHints(
            List<String> styleConstraints,
            List<String> structureConstraints,
            List<String> terminologyPolicy,
            List<String> forbiddenPatterns,
            List<String> interactionPolicy,
            List<String> reviewChecklist
    ) {
    }

    record SignalRow(
            String signalId,
            String workspaceId,
            String userId,
            String sourceType,
            String sourceId,
            String signalType,
            String signalText,
            String taskNeighborhood,
            MemoryCompileHints compileHints,
            double confidenceScore,
            String policyVersion
    ) {
    }
}
