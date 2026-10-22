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
import java.util.regex.Pattern;

/** 检查逐句核验记录完整覆盖最终正文，并严格绑定冻结事实及其联合引用。 */
final class ResearchStatementSupportValidation {
    private static final Set<String> NO_CLAIM = Set.of("—", "核验结果见正文，尚未充分回答的部分列于局限。",
            "Verified findings appear below; unanswered points are listed under limitations.");
    private static final Pattern CITATION = Pattern.compile("\\[(evidence:[^\\[\\]\\s]+)\\]");
    private static final Set<String> CHECK_FIELDS = Set.of("statement_id", "location", "text", "fact_ids", "evidence_keys", "basis", "verdict");

    void validate(ObjectMapper mapper, Map<String, Object> input, Map<String, Object> result, long chargedCalls) {
        JsonNode frozen = mapper.valueToTree(input), narrative = mapper.valueToTree(result.get("narrative"));
        JsonNode support = narrative.path("statement_support");
        if (!"research-statement-support.v1".equals(support.path("schema_version").asText())
                || !support.path("checks").isArray() || !support.path("model_calls").isIntegralNumber()
                || !support.path("model_calls").canConvertToInt()) throw invalid("报告缺少逐句核验记录");
        int calls = support.path("model_calls").asInt();
        if (calls < 0 || calls > chargedCalls || calls > frozen.path("narrative_llm_budget").asInt())
            throw invalid("逐句核验调用次数与角色预算不一致");
        Map<String, Fact> facts = new LinkedHashMap<>();
        List<String> allCells = new ArrayList<>();
        for (JsonNode cell : frozen.path("cells")) {
            String key = cell.path("cell_key").asText(); allCells.add(key);
            for (JsonNode fact : cell.path("facts")) addFact(facts, fact.path("fact_id").asText(), cell, fact.path("claim_text").asText(), keys(fact.path("evidence_keys")));
            addFact(facts, "cell:" + key, cell, cell.path("candidate_value").asText(), keys(cell.path("evidence_keys")));
        }
        Map<String, Block> blocks = new LinkedHashMap<>();
        if (frozen.path("report_metadata_version").asInt() == 1) validateMetadata(frozen,narrative);
        String summary = narrative.path("executive_summary").asText();
        blocks.put("summary", new Block(summary, allCells, citations(summary)));
        for (int si = 0; si < narrative.path("sections").size(); si++) {
            JsonNode paragraphs = narrative.path("sections").get(si).path("paragraphs");
            for (int pi = 0; pi < paragraphs.size(); pi++) {
                JsonNode paragraph = paragraphs.get(pi);
                blocks.put("sections/" + si + "/paragraphs/" + pi, new Block(paragraph.path("text").asText(),
                        keys(paragraph.path("cell_keys")), keys(paragraph.path("evidence_keys"))));
            }
        }
        JsonNode rows = narrative.path("comparison_table").path("rows");
        for (int ri = 0; ri < rows.size(); ri++) {
            JsonNode row = rows.get(ri);
            for (int ci = 1; ci < row.path("cells").size(); ci++) blocks.put("table/" + ri + "/" + ci,
                    new Block(row.path("cells").get(ci).asText(), keys(row.path("cell_keys")), keys(row.path("evidence_keys"))));
        }
        Map<String, List<JsonNode>> byLocation = new LinkedHashMap<>();
        for (JsonNode check : support.path("checks")) {
            Set<String> fields = new HashSet<>(); check.fieldNames().forEachRemaining(fields::add);
            if (!check.isObject() || !fields.equals(CHECK_FIELDS) || !blocks.containsKey(check.path("location").asText())
                    || !"SUPPORTED".equals(check.path("verdict").asText()) || !check.path("text").isTextual()
                    || check.path("text").asText().isBlank()) throw invalid("逐句核验记录格式或目标不符合约定");
            byLocation.computeIfAbsent(check.path("location").asText(), ignored -> new ArrayList<>()).add(check);
        }
        if (!byLocation.keySet().equals(blocks.keySet())) throw invalid("逐句核验记录遗漏报告片段");
        for (var entry : blocks.entrySet()) {
            Block block = entry.getValue(); StringBuilder rebuilt = new StringBuilder(); int index = 0;
            for (JsonNode check : byLocation.get(entry.getKey())) {
                if (!(entry.getKey() + "/" + index++).equals(check.path("statement_id").asText())) throw invalid("逐句核验编号重复或顺序错误");
                String prose = check.path("text").asText(), basis = check.path("basis").asText();
                List<String> ids = keys(check.path("fact_ids")), evidence = keys(check.path("evidence_keys"));
                rebuilt.append(prose);
                if ("NO_CLAIM".equals(basis)) {
                    if (!NO_CLAIM.contains(prose) || !ids.isEmpty() || !evidence.isEmpty()) throw invalid("未核验的语句包含事实性内容");
                    continue;
                }
                if (ids.isEmpty() || evidence.isEmpty() || !block.evidence().containsAll(evidence)
                        || !evidence.containsAll(citations(prose))) throw invalid("语句缺少有效事实或引用");
                Set<String> allowed = new HashSet<>();
                for (String id : ids) {
                    Fact fact = facts.get(id);
                    if (fact == null || !block.cells().contains(fact.cell()) || !evidence.containsAll(fact.evidence()))
                        throw invalid("语句引用了范围外事实或遗漏联合证据");
                    allowed.addAll(fact.evidence());
                }
                if (!allowed.containsAll(evidence)) throw invalid("语句引用与所绑定事实不对应");
                if ("FROZEN_FACT".equals(basis)) {
                    if (ids.size() != 1 || !prose.equals(facts.get(ids.get(0)).render())) throw invalid("原事实回退文本被改写");
                } else if (!"SEMANTIC_ENTAILMENT".equals(basis) || calls == 0) throw invalid("语句缺少独立语义核验");
            }
            if (!block.text().contentEquals(rebuilt)) throw invalid("逐句核验文本与最终报告不一致");
        }
    }

    private void validateMetadata(JsonNode frozen, JsonNode narrative) {
        if (!Set.of("研究报告","Research report").contains(narrative.path("title").asText())) throw invalid("报告标题包含未核验内容");
        Set<String> labels = new HashSet<>(List.of("-","研究对象","方面与结论","Subject","Aspect and finding","方面","Aspect"));
        for (JsonNode cell : frozen.path("cells")) {
            labels.add(cell.path("entity_label").asText()); labels.add(cell.path("column_label").asText());
        }
        for (JsonNode section : narrative.path("sections"))
            if (!Set.of("研究发现","Findings","补充回答","Additional answers").contains(section.path("heading").asText())) throw invalid("报告章节标题包含未核验内容");
        for (JsonNode column : narrative.path("comparison_table").path("columns"))
            if (!labels.contains(column.asText())) throw invalid("表格标题超出冻结研究范围");
        for (JsonNode row : narrative.path("comparison_table").path("rows"))
            if (!row.path("cells").isEmpty() && !labels.contains(row.path("cells").get(0).asText())) throw invalid("表格行标签超出冻结研究范围");
    }

    private void addFact(Map<String, Fact> facts, String id, JsonNode cell, String claim, List<String> evidence) {
        if (id.isBlank() || claim.isBlank() || evidence.isEmpty() || facts.containsKey(id)) throw invalid("冻结事实身份不完整或重复");
        facts.put(id, new Fact(cell.path("cell_key").asText(), cell.path("entity_label").asText(), claim, evidence));
    }

    private static List<String> keys(JsonNode node) {
        if (!node.isArray()) throw invalid("事实或引用列表格式错误");
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asText().isBlank() || result.contains(item.asText())) throw invalid("事实或引用编号为空或重复");
            result.add(item.asText());
        }
        return List.copyOf(result);
    }

    private static List<String> citations(String text) {
        List<String> result = new ArrayList<>(); var matcher = CITATION.matcher(text);
        while (matcher.find()) if (!result.contains(matcher.group(1))) result.add(matcher.group(1));
        return List.copyOf(result);
    }

    private static BusinessException invalid(String message) { return new BusinessException("RESEARCH_AGENT_ROLE_RESULT_INVALID", message); }
    private record Block(String text, List<String> cells, List<String> evidence) { }
    private record Fact(String cell, String entity, String claim, List<String> evidence) {
        String render() { return (entity.isEmpty() ? "" : entity + "：") + claim + " "
                + evidence.stream().map(key -> "[" + key + "]").collect(java.util.stream.Collectors.joining(" ")); }
    }
}
