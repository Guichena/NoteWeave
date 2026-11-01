package com.noteweave.retrieval.provider;

import java.util.Comparator;
import java.util.List;

public interface RerankClient {
    /** 重排服务不可用时由对话模型打分，模型名固定为这个值，用于区分分数来源。 */
    String LLM_FALLBACK_MODEL = "llm-listwise-v1";

    boolean isEnabled();

    RerankResult rerank(String query, List<String> documents, int topN);

    record RerankResult(List<Hit> hits, String model) {
        public RerankResult {
            hits = hits == null
                    ? List.of()
                    : hits.stream()
                            .sorted(Comparator.comparingInt(Hit::rank))
                            .toList();
            model = model == null ? "" : model;
        }

        /** 分数来自对话模型后备打分，不属于提供方重排分。 */
        public boolean llmFallback() {
            return LLM_FALLBACK_MODEL.equals(model);
        }
    }

    record Hit(int documentIndex, double score, int rank) {
    }
}
