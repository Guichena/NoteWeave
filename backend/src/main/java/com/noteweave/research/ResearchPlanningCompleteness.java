package com.noteweave.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 原问题分句在服务端冻结，规划复核必须逐项返回映射，遗漏不得声明解析完整。 */
final class ResearchPlanningCompleteness {
    static List<Map<String,String>> clauses(String question) {
        List<String> parts = java.util.Arrays.stream(question.split("[，,；;。！？?\\n、]+"))
                .map(String::strip).filter(text -> !text.isEmpty()).toList();
        if (parts.size() > 48 || parts.isEmpty()) parts = List.of(question.strip());
        List<Map<String,String>> result = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) result.add(Map.of("clause_id","clause:"+i,"text",parts.get(i)));
        return List.copyOf(result);
    }
    static void validate(ObjectMapper mapper, Map<String,Object> frozen, Map<String,Object> result, long charged) {
        JsonNode input = mapper.valueToTree(frozen), root = mapper.valueToTree(result.get("planning_completeness"));
        boolean modern = input.path(ResearchCompletionGapPolicy.FLAG).asInt() == 1;
        if (root.size() != (modern ? 5 : 4) || !(modern ? "research-planning-completeness.v2" : "research-planning-completeness.v1").equals(root.path("schema_version").asText())
                || !root.path("complete").isBoolean() || !root.path("model_calls").isIntegralNumber()
                || root.path("model_calls").asLong(-1) < 0 || root.path("model_calls").asLong() > 2 || root.path("model_calls").asLong() > charged
                || !root.path("clauses").isArray()) throw invalid();
        Set<String> expected = new HashSet<>(), seen = new HashSet<>();
        input.path("requested_clauses").forEach(item -> expected.add(item.path("clause_id").asText()));
        int requirements = mapper.valueToTree(result.get("plan")).path("user_requirements").size();
        boolean complete = !expected.isEmpty();
        Set<Integer> mapped = new HashSet<>();
        for (JsonNode clause : root.path("clauses")) {
            Set<String> fields = new HashSet<>(); clause.fieldNames().forEachRemaining(fields::add);
            String id = clause.path("clause_id").asText(), status = clause.path("status").asText();
            if (!fields.equals(Set.of("clause_id","status","requirement_indexes")) || !expected.contains(id) || !seen.add(id)
                    || !Set.of("MAPPED","MISSING","NOT_CHECKED").contains(status) || !clause.path("requirement_indexes").isArray()) throw invalid();
            Set<Integer> refs = new HashSet<>();
            for (JsonNode value : clause.path("requirement_indexes")) {
                if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0 || value.asInt() >= requirements || !refs.add(value.asInt())) throw invalid();
            }
            if ("MAPPED".equals(status) && (refs.isEmpty() || root.path("model_calls").asInt() == 0)) throw invalid();
            mapped.addAll(refs); complete &= "MAPPED".equals(status);
        }
        if (modern) {
            JsonNode checks=root.path("requirement_checks");
            if (!checks.isArray() || checks.size()!=requirements) throw invalid();
            Set<Integer> checked=new HashSet<>();
            for (JsonNode check : checks) {
                Set<String> fields=new HashSet<>();check.fieldNames().forEachRemaining(fields::add);
                JsonNode index=check.path("requirement_index"), invalid=check.path("invalid_condition_indexes");
                String status=check.path("status").asText();
                if (!fields.equals(Set.of("requirement_index","status","invalid_condition_indexes")) || !index.isIntegralNumber()
                        || !index.canConvertToInt() || index.asInt()<0 || index.asInt()>=requirements || !checked.add(index.asInt())
                        || !Set.of("VALID","EXPANDED","PROVENANCE_ONLY","NOT_CHECKED").contains(status) || !invalid.isArray()) throw invalid();
                int conditions=mapper.valueToTree(result.get("plan")).path("user_requirements").get(index.asInt()).path("conditions").size();
                Set<Integer> indexes=new HashSet<>();
                for (JsonNode condition : invalid) if (!condition.isIntegralNumber() || !condition.canConvertToInt()
                        || condition.asInt()<0 || condition.asInt()>=conditions || !indexes.add(condition.asInt())) throw invalid();
                if ("VALID".equals(status) && !indexes.isEmpty() || "EXPANDED".equals(status) && indexes.isEmpty()) throw invalid();
                complete &= "VALID".equals(status);
            }
        }
        if (!seen.equals(expected) || complete != root.path("complete").asBoolean() || complete && mapped.size() != requirements
                || modern && !complete) throw invalid();
    }
    private static BusinessException invalid() { return new BusinessException("RESEARCH_PLANNING_COMPLETENESS_INVALID","规划缺少完整且有效的原问题需求映射"); }
}
