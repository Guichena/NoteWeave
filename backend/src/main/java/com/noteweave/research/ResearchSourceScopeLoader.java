package com.noteweave.research;

import static com.noteweave.research.ResearchReadModelMapper.blankIfNull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.worker.WorkerSourceScopeItemResponse;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Loads a Research source scope in its declared order using one source query. */
@Service
public final class ResearchSourceScopeLoader {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ResearchSourceScopeLoader(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public int count(String sourceScopeJson) {
        return readSourceIds(sourceScopeJson).size();
    }

    public List<WorkerSourceScopeItemResponse> load(String workspaceId, String sourceScopeJson) {
        List<String> sourceIds = readSourceIds(sourceScopeJson);
        if (sourceIds.isEmpty()) {
            return List.of();
        }
        Object[] arguments = new Object[sourceIds.size() + 1];
        arguments[0] = workspaceId;
        for (int index = 0; index < sourceIds.size(); index++) {
            arguments[index + 1] = sourceIds.get(index);
        }
        Map<String, WorkerSourceScopeItemResponse> itemsById = jdbcTemplate.query("""
                select s.id, s.title, s.source_type, coalesce(s.summary, '') as summary,
                       ss.id as source_snapshot_id,
                       coalesce((select sw.id from source_chunk sc join source_window sw on sw.source_chunk_id = sc.id
                           where sc.source_id = s.id and sc.source_snapshot_id = ss.id
                           order by sc.chunk_no asc, sw.window_no asc limit 1), '') as source_window_id,
                       coalesce(s.generated_by, '') as generated_by,
                       coalesce(s.generated_ref_id, '') as generated_ref_id,
                       coalesce((
                           select sw.content
                           from source_chunk sc
                           join source_window sw on sw.source_chunk_id = sc.id
                           where sc.source_id = s.id and sc.source_snapshot_id = ss.id
                           order by sc.chunk_no asc, sw.window_no asc
                           limit 1
                       ), '') as sample_text
                from source s
                left join source_snapshot ss on ss.source_id = s.id
                  and ss.version_no = (select max(current_ss.version_no)
                      from source_snapshot current_ss where current_ss.source_id = s.id)
                where s.workspace_id = ?
                  and s.id in (%s)
                  and s.status = 'READY'
                """.formatted(String.join(",", Collections.nCopies(sourceIds.size(), "?"))), rs -> {
            LinkedHashMap<String, WorkerSourceScopeItemResponse> items = new LinkedHashMap<>();
            while (rs.next()) {
                String generatedBy = blankIfNull(rs.getString("generated_by"));
                String generatedRefId = blankIfNull(rs.getString("generated_ref_id"));
                WorkerSourceScopeItemResponse item = new WorkerSourceScopeItemResponse(
                        rs.getString("id"),
                        rs.getString("title"),
                        rs.getString("summary"),
                        rs.getString("sample_text"),
                        blankIfNull(rs.getString("source_snapshot_id")),
                        blankIfNull(rs.getString("source_window_id")),
                        generatedBy,
                        generatedRefId,
                        blankIfNull(rs.getString("source_type")),
                        "",
                        sourceMetadata(generatedBy, generatedRefId)
                );
                items.put(item.sourceId(), item);
            }
            return items;
        }, arguments);
        List<WorkerSourceScopeItemResponse> result = sourceIds.stream().map(itemsById::get).toList();
        if (result.stream().anyMatch(java.util.Objects::isNull)) {
            throw new BusinessException(
                    "RESEARCH_SOURCE_SCOPE_UNAVAILABLE",
                    "研究资料范围包含已删除、未就绪或不属于当前工作台的资料");
        }
        return result;
    }

    private List<String> readSourceIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> value = objectMapper.readValue(json, new TypeReference<>() {
            });
            if (value == null || value.stream().anyMatch(item -> item == null || item.isBlank())) {
                throw new BusinessException(
                        "RESEARCH_SOURCE_SCOPE_PARSE_FAILED", "研究资料范围必须是非空字符串数组");
            }
            return value;
        } catch (JsonProcessingException ex) {
            throw new BusinessException(
                    "RESEARCH_SOURCE_SCOPE_PARSE_FAILED",
                    "研究资料范围解析失败"
            );
        }
    }

    private Map<String, Object> sourceMetadata(String generatedBy, String generatedRefId) {
        if (!"research_agent".equals(generatedBy) || generatedRefId.isBlank()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("artifact_id", generatedRefId);
        artifact.put("artifact_type", "DEEP_RESEARCH_REPORT");
        artifact.put("generated_by", generatedBy);
        artifact.put("generated_ref_id", generatedRefId);
        return Map.of("research_artifact", artifact);
    }
}
