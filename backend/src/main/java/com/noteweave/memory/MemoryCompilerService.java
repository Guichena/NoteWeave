package com.noteweave.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MemoryCompilerService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MemoryCompilerService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public MemoryControlPackResponse compileChatControlPack(String workspaceId, String answerMode) {
        String normalizedMode = MemorySignalService.normalizeToken(answerMode);
        Set<String> neighborhoods = new LinkedHashSet<>();
        neighborhoods.add("COMMON");
        neighborhoods.add("CHAT");
        neighborhoods.add("CHAT_" + normalizedMode);
        return compilePack(workspaceId, "chat", normalizedMode, "CHAT_" + normalizedMode, neighborhoods,
                List.of("Memory 不作为事实来源，事实内容必须来自工作台资料池、原文窗口或 Wiki 页面"));
    }

    public MemoryControlPackResponse compileArtifactControlPack(String workspaceId, String actionKey) {
        String normalizedAction = MemorySignalService.normalizeToken(actionKey);
        Set<String> neighborhoods = new LinkedHashSet<>();
        neighborhoods.add("COMMON");
        neighborhoods.add("ARTIFACT");
        neighborhoods.add("ARTIFACT_" + normalizedAction);
        return compilePack(workspaceId, "artifact", normalizedAction, "ARTIFACT_" + normalizedAction, neighborhoods,
                List.of(
                        "产物事实必须来自工作台资料池或已保存为资料的系统产物",
                        "Memory 不作为产物生成原材料"
                ));
    }

    public MemoryControlPackResponse compileResearchControlPack(String workspaceId, String profileKey) {
        String normalizedProfile = MemorySignalService.normalizeToken(profileKey);
        Set<String> neighborhoods = new LinkedHashSet<>();
        neighborhoods.add("COMMON");
        neighborhoods.add("RESEARCH");
        neighborhoods.add("RESEARCH_" + normalizedProfile);
        return compilePack(workspaceId, "research", normalizedProfile, "RESEARCH_" + normalizedProfile, neighborhoods,
                List.of(
                        "Memory 不作为研究证据",
                        "研究结论必须由搜索结果、工作台资料或验证证据支持"
                ));
    }

    public void logPackUsage(String workspaceId, String taskType, String targetType, String targetId, MemoryControlPackResponse pack) {
        if (pack == null || pack.memoryObjectIds().isEmpty()) {
            return;
        }
        for (String memoryObjectId : pack.memoryObjectIds()) {
            jdbcTemplate.update("""
                    insert into memory_usage_log(id, memory_object_id, workspace_id, task_type, target_type, target_id, compiled_as)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """,
                    Ids.newId(),
                    memoryObjectId,
                    workspaceId,
                    taskType,
                    targetType,
                    targetId,
                    pack.packType()
            );
        }
    }

    private MemoryControlPackResponse compilePack(
            String workspaceId,
            String packType,
            String targetKey,
            String taskNeighborhood,
            Set<String> allowedNeighborhoods,
            List<String> evidencePolicy
    ) {
        List<ObjectRow> rows = jdbcTemplate.query("""
                select id, task_neighborhood_json, compile_policy_json, status
                from memory_object
                where workspace_id = ? and status = 'ACTIVE'
                order by created_at asc
                """, (rs, rowNum) -> new ObjectRow(
                rs.getString("id"),
                readStringList(rs.getString("task_neighborhood_json")),
                readCompileHints(rs.getString("compile_policy_json")),
                rs.getString("status")
        ), workspaceId);

        List<String> memoryObjectIds = new ArrayList<>();
        Set<String> styleConstraints = new LinkedHashSet<>();
        Set<String> structureConstraints = new LinkedHashSet<>();
        Set<String> terminologyPolicy = new LinkedHashSet<>();
        Set<String> forbiddenPatterns = new LinkedHashSet<>();
        Set<String> interactionPolicy = new LinkedHashSet<>();
        Set<String> reviewChecklist = new LinkedHashSet<>();

        for (ObjectRow row : rows) {
            if (!matchesNeighborhood(row.taskNeighborhoods(), allowedNeighborhoods)) {
                continue;
            }
            memoryObjectIds.add(row.memoryObjectId());
            styleConstraints.addAll(row.compileHints().styleConstraints());
            structureConstraints.addAll(row.compileHints().structureConstraints());
            terminologyPolicy.addAll(row.compileHints().terminologyPolicy());
            forbiddenPatterns.addAll(row.compileHints().forbiddenPatterns());
            interactionPolicy.addAll(row.compileHints().interactionPolicy());
            reviewChecklist.addAll(row.compileHints().reviewChecklist());
        }

        return new MemoryControlPackResponse(
                packType,
                targetKey,
                taskNeighborhood,
                List.copyOf(styleConstraints),
                List.copyOf(structureConstraints),
                List.copyOf(terminologyPolicy),
                List.copyOf(forbiddenPatterns),
                List.copyOf(evidencePolicy),
                List.copyOf(interactionPolicy),
                List.copyOf(reviewChecklist),
                List.copyOf(memoryObjectIds)
        );
    }

    private boolean matchesNeighborhood(List<String> objectNeighborhoods, Set<String> allowedNeighborhoods) {
        for (String neighborhood : objectNeighborhoods) {
            if (allowedNeighborhoods.contains(neighborhood)) {
                return true;
            }
        }
        return false;
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

    private record ObjectRow(
            String memoryObjectId,
            List<String> taskNeighborhoods,
            MemorySignalService.MemoryCompileHints compileHints,
            String status
    ) {
    }
}
