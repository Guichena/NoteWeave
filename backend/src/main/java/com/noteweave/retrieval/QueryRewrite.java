package com.noteweave.retrieval;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次查询改写的结果，以及它在 RetrievalPlan.filters 里的读写方式。
 * 回答接口上的 retrieval_rewrite 也从这里还原。
 */
public record QueryRewrite(
        boolean standalone,
        String standaloneQuery,
        String keywordQuery,
        List<String> alternatives,
        String questionType,
        List<String> mustTerms,
        List<String> lowLevelKeywords,
        List<String> highLevelKeywords,
        String language,
        String method,
        List<String> degradationReasons
) {
    public static final String FILTER_IS_STANDALONE = "rewrite_is_standalone";
    public static final String FILTER_STANDALONE_QUERY = "standalone_query";
    public static final String FILTER_KEYWORD_QUERY = "keyword_query";
    public static final String FILTER_ALTERNATIVES = "rewrite_alternatives";
    public static final String FILTER_LOW_LEVEL = "low_level_keywords";
    public static final String FILTER_HIGH_LEVEL = "high_level_keywords";
    public static final String FILTER_METHOD = "rewrite_method";
    public static final String FILTER_DEGRADATION = "rewrite_degradation_reasons";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> COPIED_KEYS = List.of(
            QaRetrievalStrategyProfile.FILTER_ORIGINAL_QUERY,
            QaRetrievalStrategyProfile.FILTER_RETRIEVAL_QUERIES,
            QaRetrievalStrategyProfile.FILTER_MUST_TERMS,
            QaRetrievalStrategyProfile.FILTER_QUESTION_TYPE,
            QaRetrievalStrategyProfile.FILTER_LANGUAGE,
            FILTER_IS_STANDALONE,
            FILTER_STANDALONE_QUERY,
            FILTER_KEYWORD_QUERY,
            FILTER_ALTERNATIVES,
            FILTER_LOW_LEVEL,
            FILTER_HIGH_LEVEL,
            FILTER_METHOD,
            FILTER_DEGRADATION
    );

    public QueryRewrite {
        standaloneQuery = standaloneQuery == null ? "" : standaloneQuery;
        keywordQuery = keywordQuery == null || keywordQuery.isBlank() ? standaloneQuery : keywordQuery;
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        questionType = questionType == null || questionType.isBlank() ? "definition" : questionType;
        mustTerms = mustTerms == null ? List.of() : List.copyOf(mustTerms);
        lowLevelKeywords = lowLevelKeywords == null ? List.of() : List.copyOf(lowLevelKeywords);
        highLevelKeywords = highLevelKeywords == null ? List.of() : List.copyOf(highLevelKeywords);
        language = language == null || language.isBlank() ? "zh" : language;
        method = method == null || method.isBlank() ? "RULE" : method;
        degradationReasons = degradationReasons == null ? List.of() : List.copyOf(degradationReasons);
    }

    /** 写进回答上下文，再由各模式的 plan 复制到 RetrievalPlan.filters。 */
    public Map<String, String> attributes(String originalQuestion) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(QaRetrievalStrategyProfile.FILTER_ORIGINAL_QUERY,
                originalQuestion == null ? "" : originalQuestion);
        attributes.put(QaRetrievalStrategyProfile.FILTER_RETRIEVAL_QUERIES, retrievalQueriesJson());
        attributes.put(QaRetrievalStrategyProfile.FILTER_MUST_TERMS, strings(mustTerms));
        attributes.put(QaRetrievalStrategyProfile.FILTER_QUESTION_TYPE, questionType);
        attributes.put(QaRetrievalStrategyProfile.FILTER_LANGUAGE, language);
        attributes.put(FILTER_IS_STANDALONE, Boolean.toString(standalone));
        attributes.put(FILTER_STANDALONE_QUERY, standaloneQuery);
        attributes.put(FILTER_KEYWORD_QUERY, keywordQuery);
        attributes.put(FILTER_ALTERNATIVES, strings(alternatives));
        attributes.put(FILTER_LOW_LEVEL, strings(lowLevelKeywords));
        attributes.put(FILTER_HIGH_LEVEL, strings(highLevelKeywords));
        attributes.put(FILTER_METHOD, method);
        attributes.put(FILTER_DEGRADATION, strings(degradationReasons));
        return attributes;
    }

    public RetrievalChannels channels() {
        boolean expandByRule = !"LLM".equals(method);
        List<String> extra = expandByRule ? List.of() : alternatives;
        return new RetrievalChannels(
                standaloneQuery,
                keywordQuery,
                standaloneQuery,
                extra,
                mustTerms,
                lowLevelKeywords,
                highLevelKeywords,
                expandByRule
        );
    }

    public View view() {
        return new View(standalone, standaloneQuery, keywordQuery, method, degradationReasons);
    }

    /** 只复制改写写入的键，没有改写结果时保持调用方原来的 filters。 */
    public static void copyInto(Map<String, String> filters, Map<String, String> attributes) {
        if (filters == null || attributes == null || !attributes.containsKey(FILTER_METHOD)) {
            return;
        }
        for (String key : COPIED_KEYS) {
            String value = attributes.get(key);
            if (value != null) {
                filters.put(key, value);
            }
        }
    }

    /**
     * 计划里没有改写结果时，三个通道都退回 fallback，并允许规则扩展。
     * 这样直接构造的旧计划仍按原查询检索。
     */
    public static RetrievalChannels channelsFrom(Map<String, String> filters, String fallback) {
        if (filters == null || !filters.containsKey(FILTER_METHOD)) {
            return RetrievalChannels.same(fallback);
        }
        String vector = text(filters.get(FILTER_STANDALONE_QUERY), fallback);
        String lexical = text(filters.get(FILTER_KEYWORD_QUERY), vector);
        boolean expandByRule = !"LLM".equals(filters.get(FILTER_METHOD));
        List<String> alternatives = expandByRule
                ? List.of()
                : readStrings(filters.get(FILTER_ALTERNATIVES));
        return new RetrievalChannels(
                vector,
                lexical,
                vector,
                alternatives,
                readStrings(filters.get(QaRetrievalStrategyProfile.FILTER_MUST_TERMS)),
                readStrings(filters.get(FILTER_LOW_LEVEL)),
                readStrings(filters.get(FILTER_HIGH_LEVEL)),
                expandByRule
        );
    }

    /** 从已保存的 retrieval_plan_json 还原回答接口上的 retrieval_rewrite。没有改写字段时返回 null。 */
    public static View viewFromPlanJson(String planJson) {
        if (planJson == null || planJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = readJson(planJson);
            JsonNode steps = root.path("steps");
            if (!steps.isArray() || steps.isEmpty()) {
                return null;
            }
            JsonNode filters = steps.get(0).path("filters");
            if (!filters.isObject() || !filters.has(FILTER_METHOD)) {
                return null;
            }
            return new View(
                    filters.path(FILTER_IS_STANDALONE).asBoolean(false),
                    filters.path(FILTER_STANDALONE_QUERY).asText(""),
                    filters.path(FILTER_KEYWORD_QUERY).asText(""),
                    filters.path(FILTER_METHOD).asText("RULE"),
                    readStrings(filters.path(FILTER_DEGRADATION).asText("[]"))
            );
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private String retrievalQueriesJson() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("vector", standaloneQuery);
        payload.put("keyword", keywordQuery);
        payload.put("alternatives", alternatives);
        try {
            return JSON.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            return standaloneQuery;
        }
    }

    static String strings(List<String> values) {
        try {
            return JSON.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    static List<String> readStrings(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("[")) {
            try {
                JsonNode node = JSON.readTree(trimmed);
                if (!node.isArray()) {
                    return List.of(trimmed);
                }
                List<String> values = new ArrayList<>();
                for (JsonNode item : node) {
                    String text = item.asText("").trim();
                    if (!text.isBlank()) {
                        values.add(text);
                    }
                }
                return List.copyOf(values);
            } catch (JsonProcessingException ex) {
                return List.of(trimmed);
            }
        }
        return List.of(trimmed);
    }

    private static JsonNode readJson(String raw) throws JsonProcessingException {
        JsonNode node = JSON.readTree(raw);
        if (node.isTextual()) {
            return JSON.readTree(node.asText());
        }
        return node;
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? (fallback == null ? "" : fallback) : value;
    }

    public record View(
            @JsonProperty("is_standalone") boolean isStandalone,
            String standaloneQuery,
            String keywordQuery,
            String method,
            List<String> degradationReasons
    ) {
        public View {
            standaloneQuery = standaloneQuery == null ? "" : standaloneQuery;
            keywordQuery = keywordQuery == null ? "" : keywordQuery;
            method = method == null || method.isBlank() ? "RULE" : method;
            degradationReasons = degradationReasons == null ? List.of() : List.copyOf(degradationReasons);
        }
    }
}
