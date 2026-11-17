package com.noteweave.research;

import com.noteweave.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** 新运行显式冻结需求类型和条件，旧运行继续使用原快照。 */
final class ResearchCompletionGapPolicy {
    static final String FLAG = "completion_gap_policy_version";

    static boolean enabled(JdbcTemplate jdbc, String runId) {
        String flags = jdbc.query("select agent_feature_flags_json from research_run where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, runId);
        try {
            var mapper = new ObjectMapper();
            var node = mapper.readTree(flags == null ? "{}" : flags);
            if (node.isTextual()) node=mapper.readTree(node.asText());
            return node.path("requirement_coverage_v1").asBoolean() && node.path(FLAG).asInt() == 1;
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) { return false; }
    }

    static Map<String,Object> fields(Map<?,?> raw, String question) {
        String kind = String.valueOf(raw.get("answer_kind")), temporal = String.valueOf(raw.get("temporal_mode"));
        if (!Set.of("FACT","IDENTIFIER").contains(kind)
                || !Set.of("CURRENT","HISTORICAL","TIME_INVARIANT","UNSPECIFIED").contains(temporal)
                || "IDENTIFIER".equals(kind) && !"TIME_INVARIANT".equals(temporal)) throw invalid();
        if (!(raw.get("conditions") instanceof List<?> conditions) || conditions.isEmpty() || conditions.size() > 8
                || conditions.stream().anyMatch(value -> !(value instanceof String text) || text.isBlank() || text.length() > 350)
                || conditions.stream().distinct().count() != conditions.size()) throw invalid();
        if (!(raw.get("source_constraints") instanceof List<?> constraints) || constraints.size() > 4) throw invalid();
        List<Map<String,Object>> sources = new ArrayList<>();
        for (Object value : constraints) {
            if (!(value instanceof Map<?,?> constraint) || !(constraint.keySet().equals(Set.of("policy","question_quote")) || constraint.keySet().equals(Set.of("policy","question_quote","source_types")))
                    || !"PRIMARY_SOURCE".equals(constraint.get("policy")) || !(constraint.get("question_quote") instanceof String quote)
                    || quote.isBlank() || quote.length() > 1000 || !normalize(question).contains(normalize(quote))) throw invalid();
            Object rawTypes=constraint.containsKey("source_types") ? constraint.get("source_types") : List.of("PRIMARY_SOURCE");
            if (!(rawTypes instanceof List<?> types) || types.isEmpty() || types.size()>4 || types.stream().distinct().count()!=types.size()
                    || types.stream().anyMatch(type -> !Set.of("PRIMARY_SOURCE","SPECIFICATION","OFFICIAL_DOCUMENTATION","ORIGINAL_RESEARCH").contains(type))) throw invalid();
            sources.add(Map.of("policy","PRIMARY_SOURCE","question_quote",quote,"source_types",List.copyOf(types)));
        }
        return Map.of("answer_kind",kind,"temporal_mode",temporal,"conditions",List.copyOf(conditions),"source_constraints",List.copyOf(sources));
    }

    static Map<String,Object> scope(Map<String,Object> inherited, Map<String,Object> definition) {
        Map<String,Object> result = new LinkedHashMap<>(inherited);
        result.put("answer_definition", definition);
        return result;
    }

    private static String normalize(String text) { return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC).replaceAll("(?U)\\s+"," ").strip(); }
    private static BusinessException invalid() { return new BusinessException("RESEARCH_REQUIREMENT_DEFINITION_INVALID", "需求类型、时间范围、独立条件或来源约束不符合约定"); }
    private ResearchCompletionGapPolicy() { }
}
