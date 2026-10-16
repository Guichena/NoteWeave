package com.noteweave.retrieval;

import java.util.List;

/**
 * 一次检索里各通道实际使用的查询。
 * 向量和 Rerank 用独立问题，BM25 用关键词查询。
 */
public record RetrievalChannels(
        String vectorQuery,
        String lexicalQuery,
        String rerankQuery,
        List<String> alternatives,
        List<String> mustTerms,
        List<String> titleTerms,
        List<String> bodyTerms,
        boolean ruleExpansion
) {
    public RetrievalChannels {
        vectorQuery = vectorQuery == null ? "" : vectorQuery;
        lexicalQuery = lexicalQuery == null || lexicalQuery.isBlank() ? vectorQuery : lexicalQuery;
        rerankQuery = rerankQuery == null || rerankQuery.isBlank() ? vectorQuery : rerankQuery;
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        mustTerms = mustTerms == null ? List.of() : List.copyOf(mustTerms);
        titleTerms = titleTerms == null ? List.of() : List.copyOf(titleTerms);
        bodyTerms = bodyTerms == null ? List.of() : List.copyOf(bodyTerms);
    }

    /** 三个通道使用同一句查询，低召回时允许规则扩展。供尚未改写的调用方使用。 */
    public static RetrievalChannels same(String query) {
        String value = query == null ? "" : query;
        return new RetrievalChannels(value, value, value, List.of(), List.of(), List.of(), List.of(), true);
    }

    public boolean hasFieldBoosts() {
        return !mustTerms.isEmpty() || !titleTerms.isEmpty() || !bodyTerms.isEmpty();
    }

    /**
     * 三个通道相同、没有加权、也没有模型备选查询。
     * 这时继续走原来的单查询方法，避免旧测试和旧计划改变行为。
     */
    public boolean legacySingleQuery() {
        return ruleExpansion
                && alternatives.isEmpty()
                && !hasFieldBoosts()
                && vectorQuery.equals(lexicalQuery)
                && vectorQuery.equals(rerankQuery);
    }
}
