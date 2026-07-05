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
public class MemorySignalService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MemorySignalService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public MemorySignalResponse createSignal(String workspaceId, CreateMemorySignalRequest request) {
        requireWorkspace(workspaceId);
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
        double confidenceScore = estimateConfidence(sourceType);
        jdbcTemplate.update("""
                insert into memory_signal(
                    id, workspace_id, user_id, source_type, source_id, signal_type, signal_text,
                    task_neighborhood, compile_hints_json, confidence_score
                ) values (?, ?, 'local-user', ?, ?, ?, ?, ?, ?, ?)
                """,
                signalId,
                workspaceId,
                sourceType,
                blankToNull(request.sourceId()),
                signalType,
                request.signalText().trim(),
                taskNeighborhood,
                Json.write(objectMapper, hints),
                confidenceScore
        );
        return new MemorySignalResponse(
                signalId,
                workspaceId,
                signalType,
                sourceType,
                blankToNull(request.sourceId()),
                request.signalText().trim(),
                taskNeighborhood,
                confidenceScore
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
                           task_neighborhood, compile_hints_json, confidence_score
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
                        rs.getDouble("confidence_score")
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

    private void requireWorkspace(String workspaceId) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from workspace where id = ?", Integer.class, workspaceId);
        if (count == null || count == 0) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在");
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

    private double estimateConfidence(String sourceType) {
        return switch (sourceType) {
            case "USER_FEEDBACK" -> 0.98;
            case "PROJECT_DECISION" -> 0.97;
            case "ARTIFACT_FEEDBACK" -> 0.78;
            case "CONVERSATION_FEEDBACK" -> 0.72;
            case "MODEL_INFERENCE" -> 0.35;
            default -> 0.60;
        };
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
            double confidenceScore
    ) {
    }
}
