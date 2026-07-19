package com.noteweave.retrieval.provider;

import java.util.List;

public interface EmbeddingClient {
    boolean isEnabled();

    EmbeddingResult embedQuery(String text);

    EmbeddingResult embedDocuments(List<String> texts);

    record EmbeddingResult(
            List<List<Float>> vectors,
            String model,
            int dimensions,
            long totalTokens
    ) {
        public EmbeddingResult {
            vectors = vectors == null
                    ? List.of()
                    : vectors.stream().map(List::copyOf).toList();
            model = model == null ? "" : model;
        }

        public List<Float> singleVector() {
            if (vectors.size() != 1) {
                throw new IllegalStateException("Expected one embedding vector but received " + vectors.size());
            }
            return vectors.get(0);
        }
    }
}
