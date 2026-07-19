package com.noteweave.retrieval.provider;

import java.util.Comparator;
import java.util.List;

public interface RerankClient {
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
    }

    record Hit(int documentIndex, double score, int rank) {
    }
}
