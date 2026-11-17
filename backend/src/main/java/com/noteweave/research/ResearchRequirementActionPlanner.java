package com.noteweave.research;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 只选择下一步动作，不改变事实资格、反证要求或任务租约。 */
final class ResearchRequirementActionPlanner {
    static Map<String, Object> plan(JsonNode contract, String cell, boolean conflict, boolean pendingProof, int noGainRounds) {
        Set<String> missing = new java.util.HashSet<>(); contract.path("missing_requirement_ids").forEach(id -> missing.add(id.asText()));
        List<String> ids = new ArrayList<>(), texts = new ArrayList<>(), claims = new ArrayList<>(), urls = new ArrayList<>();
        int priority = 0;
        boolean modern = contract.path(ResearchCompletionGapPolicy.FLAG).asInt() == 1;
        List<Map<String,Object>> conditions = new ArrayList<>();
        for (JsonNode requirement : contract.path("requirements")) {
            if (!cell.equals(requirement.path("cell_key").asText()) || !missing.contains(requirement.path("requirement_id").asText())) continue;
            if (modern && !requirement.path("mandatory").asBoolean(true)) continue;
            ids.add(requirement.path("requirement_id").asText()); texts.add(requirement.path("text").asText());
            if (modern) {
                var answer = requirement.path("scope").path("answer_contract");
                conditions.add(Map.of("requirement_id",requirement.path("requirement_id").asText(),
                        "temporal_mode",requirement.path("scope").path("temporal_mode").asText("UNSPECIFIED"),
                        "answer_kind",answer.path("answer_kind").asText("FACT"),
                        "conditions",answer.path("conditions").isArray() ? answer.path("conditions") : List.of(requirement.path("text").asText())));
            }
            priority += "EXPLICIT".equals(requirement.path("origin").asText()) ? 100 : 20;
        }
        for (JsonNode fact : contract.path("facts")) if (cell.equals(fact.path("cell_key").asText())) claims.add(fact.path("claim_text").asText());
        for (JsonNode material : contract.path("deferred_materials")) {
            if (!cell.equals(material.path("cell_key").asText())) continue;
            if (modern && java.util.stream.StreamSupport.stream(material.path("requirement_ids").spliterator(),false).noneMatch(id -> ids.contains(id.asText()))) continue;
            String url = material.path("source_url").asText();
            if ((url.startsWith("https://") || url.startsWith("http://")) && !urls.contains(url) && urls.size() < 3) urls.add(url);
        }
        if (modern && !ids.isEmpty()) for (JsonNode material : contract.path("related_materials")) {
            if (!cell.equals(material.path("cell_key").asText())) continue;
            String url = material.path("source_url").asText();
            if ((url.startsWith("https://") || url.startsWith("http://")) && !urls.contains(url) && urls.size() < 3) urls.add(url);
        }
        boolean read = !urls.isEmpty() && noGainRounds < 2;
        String action = conflict ? "RESOLVE_CONFLICT" : read ? "READ_TARGETED"
                : pendingProof && claims.isEmpty() ? "CORROBORATE" : "POPULATE_REQUIREMENTS";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cell_key", cell); result.put("action", action); result.put("missing_requirement_ids", ids);
        result.put("missing_requirement_texts", texts); result.put("qualified_claims", claims); result.put("read_urls", urls);
        result.put("no_gain_rounds", noGainRounds); result.put("priority_score", priority);
        if (modern) result.put("condition_targets",conditions);
        result.put("reason_code", conflict ? "EVIDENCE_CONFLICT" : read ? "DEFERRED_ARCHIVED_MATERIAL"
                : "CORROBORATE".equals(action) ? "INDEPENDENT_PROOF_MISSING" : "REQUIREMENT_FACTS_MISSING");
        return Map.copyOf(result);
    }
}
