package com.noteweave.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** 延后材料只是下轮阅读提示，必须重新经过归档、事实和来源资格核验。 */
final class ResearchDeferredMaterialService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    ResearchDeferredMaterialService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    List<Map<String, Object>> load(String run, String cell, int plan, int entities) {
        List<String> diagnostics = jdbc.queryForList("""
                select execution.extraction_diagnostics_json from research_agent_execution execution
                join research_agent_task task on task.id = execution.research_agent_task_id
                where task.research_run_id = ? and task.plan_revision = ? and task.entity_set_version = ?
                  and task.target_cells_json like ?
                  and execution.extraction_diagnostics_json is not null
                order by task.wave_no desc, execution.id desc limit 12
                """, String.class, run, plan, entities, "%\"" + cell + "\"%");
        Map<String, Map<String, Object>> pending = new LinkedHashMap<>();
        for (String json : diagnostics) {
            try {
                JsonNode root = mapper.readTree(json); if (root.isTextual()) root = mapper.readTree(root.asText());
                for (JsonNode item : root.path("fact_selection").path("deferred")) {
                    if (!cell.equals(item.path("cell_key").asText()) || pending.size() >= 24) continue;
                    pending.putIfAbsent(item.path("material_id").asText(), mapper.convertValue(item, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }));
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw invalid("已保存的事实选择诊断无法解析");
            }
        }
        return List.copyOf(pending.values());
    }

    static void validate(Object raw) {
        JsonNode node = new ObjectMapper().valueToTree(raw);
        if (!fields(node).equals(Set.of("schema_version", "considered_count", "submitted_evidence_keys", "limit", "deferred", "omitted_count"))
                || !"research-fact-selection.v1".equals(node.path("schema_version").asText())) throw invalid("事实选择诊断格式错误");
        for (String key : List.of("considered_count", "limit", "omitted_count"))
            if (!node.path(key).isIntegralNumber() || !node.path(key).canConvertToInt() || node.path(key).asInt() < 0 || node.path(key).asInt() > 10000) throw invalid("事实选择计数不符合约定");
        List<String> selected = strings(node.path("submitted_evidence_keys"), 12, 64);
        if (!node.path("deferred").isArray() || node.path("deferred").size() > 24 || selected.size() > node.path("limit").asInt()
                || node.path("considered_count").asInt() != selected.size() + node.path("deferred").size() + node.path("omitted_count").asInt()) throw invalid("事实选择计数不一致");
        Set<String> ids = new HashSet<>();
        for (JsonNode item : node.path("deferred")) {
            if (!fields(item).equals(Set.of("material_id", "cell_key", "claim_text", "source_id", "window_id", "source_url", "requirement_ids", "reason_code"))
                    || !Set.of("COMPLETION_EVIDENCE_LIMIT","COMPLETION_PAYLOAD_LIMIT").contains(item.path("reason_code").asText())) throw invalid("延后材料字段错误");
            for (String key : List.of("material_id", "cell_key", "claim_text", "source_id", "window_id", "source_url")) {
                int maximum = "claim_text".equals(key) ? 1200 : "source_url".equals(key) ? 2000 : "material_id".equals(key) ? 64 : 160;
                if (!item.path(key).isTextual() || item.path(key).asText().codePointCount(0, item.path(key).asText().length()) > maximum
                        || (!"source_url".equals(key) && item.path(key).asText().isBlank())) throw invalid("延后材料字段为空或过长");
            }
            if (!ids.add(item.path("material_id").asText())) throw invalid("延后材料身份重复");
            strings(item.path("requirement_ids"), 192, 64);
        }
    }

    private static Set<String> fields(JsonNode node) { Set<String> result = new HashSet<>(); node.fieldNames().forEachRemaining(result::add); return result; }
    private static List<String> strings(JsonNode node, int maximum, int length) {
        if (!node.isArray() || node.size() > maximum) throw invalid("事实选择引用列表错误");
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asText().isBlank() || item.asText().length() > length || result.contains(item.asText())) throw invalid("事实选择引用编号错误");
            result.add(item.asText());
        }
        return List.copyOf(result);
    }
    private static BusinessException invalid(String message) { return new BusinessException("RESEARCH_FACT_SELECTION_INVALID", message); }
}
