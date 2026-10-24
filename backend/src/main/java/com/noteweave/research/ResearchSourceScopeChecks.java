package com.noteweave.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 语义回执必须绑定冻结事实与归档原文；明确不符的来源拒收，无法判断的保留并在当前状态需求上另行核对。 */
final class ResearchSourceScopeChecks {
    static void validate(Object raw) {
        JsonNode root = new ObjectMapper().valueToTree(raw);
        if (root.size() != 3 || !"research-source-scope-checks.v1".equals(root.path("schema_version").asText())
                || !root.path("model_calls").isIntegralNumber() || root.path("model_calls").asLong(-1) < 0 || root.path("model_calls").asLong() > 3
                || !root.path("checks").isArray() || root.path("checks").size() > 12) throw invalid();
        Set<String> seen = new HashSet<>();
        for (JsonNode check : root.path("checks")) {
            Set<String> fields = new HashSet<>(); check.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(Set.of("cluster_key","cell_key","product","branch","time","date","date_kind","date_quote"))
                    || !check.path("cluster_key").isTextual() || !seen.add(check.path("cluster_key").asText())
                    || !check.path("cell_key").isTextual() || !check.path("date").isTextual() || !check.path("date_quote").isTextual()
                    || check.path("cluster_key").asText().isBlank() || check.path("cluster_key").asText().length() > 64
                    || check.path("cell_key").asText().isBlank() || check.path("cell_key").asText().length() > 160
                    || "MATCH".equals(check.path("product").asText()) && root.path("model_calls").asInt() == 0
                    || !Set.of("PUBLISHED","UPDATED","UNKNOWN").contains(check.path("date_kind").asText())
                    || check.path("date_quote").asText().length() > 1200 || check.path("date").asText().length() > 10
                    || !Set.of("MATCH","MISMATCH","UNKNOWN").contains(check.path("product").asText())
                    || !Set.of("APPLICABLE","MISMATCH","UNKNOWN").contains(check.path("branch").asText())
                    || !Set.of("APPLICABLE","HISTORICAL","AFTER_CUTOFF","UNKNOWN").contains(check.path("time").asText())) throw invalid();
        }
    }
    static JsonNode check(ResearchAgentCompletionEnvelope envelope, JsonNode cluster, JsonNode scope,
            Map<String,ResearchAgentCompletionCommitter.TrustedEvidenceSource> authority, List<String> reasons) {
        Object raw = envelope.extractionDiagnostics().get("source_scope_checks");
        if (raw == null) throw invalid();
        validate(raw);
        JsonNode root = new ObjectMapper().valueToTree(raw), selected = null;
        if (root.path("model_calls").asLong() > envelope.budgetUsage().getOrDefault("llm_calls",0L)) throw invalid();
        for (JsonNode item : root.path("checks")) if (item.path("cluster_key").equals(cluster.path("cluster_key"))) selected = item;
        if (selected == null || !selected.path("cell_key").equals(cluster.path("cell_key"))) throw invalid();
        // 无法判断（包括核验调用失败后的回退值）记为 UNKNOWN，只有明确不符才拒收；
        // 当前状态需求是否能用未标日期的来源，由 canAnswerCurrent 按文档是否带版本号再判断
        if ("MISMATCH".equals(selected.path("product").asText())) reasons.add("SOURCE_PRODUCT_MISMATCH");
        if ("MISMATCH".equals(selected.path("branch").asText())) reasons.add("SOURCE_BRANCH_MISMATCH");
        String temporal = selected.path("time").asText();
        if ("AFTER_CUTOFF".equals(temporal)
                || "CURRENT".equals(scope.path("temporal_mode").asText()) && "HISTORICAL".equals(temporal)
                    && !scope.path("allow_historical_facts").asBoolean()) reasons.add("SOURCE_TIME_" + temporal);
        String date = selected.path("date").asText(), quote = selected.path("date_quote").asText();
        if (date.isBlank() && "REQUESTED_DATE".equals(scope.path("cutoff_mode").asText()) && "CURRENT".equals(scope.path("temporal_mode").asText()))
            reasons.add("SOURCE_DATE_UNRESOLVED");
        if (!date.isBlank()) {
            try {
                LocalDate parsed = LocalDate.parse(date);
                boolean grounded = false;
                for (JsonNode key : cluster.path("evidence_keys")) {
                    var source = authority.get(key.asText());
                    if (source != null && !quote.isBlank() && source.scopeText().contains(quote)
                            && ResearchSourceDateGrounding.matches(quote, parsed)) grounded = true;
                }
                if (!grounded) reasons.add("SOURCE_DATE_UNGROUNDED");
                if (parsed.isAfter(LocalDate.parse(scope.path("as_of_date").asText()))) reasons.add("SOURCE_AFTER_CUTOFF");
            } catch (RuntimeException error) { reasons.add("SOURCE_DATE_INVALID"); }
        } else if (!quote.isBlank()) throw invalid();
        if (root.path("model_calls").asInt() == 0 && "MATCH".equals(selected.path("product").asText())) throw invalid();
        return selected;
    }
    private static BusinessException invalid() { return new BusinessException("RESEARCH_SOURCE_SCOPE_CHECK_INVALID", "来源范围核验格式或事实绑定不符合约定"); }
}
