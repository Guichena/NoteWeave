package com.noteweave.retrieval.provider;

import java.util.List;

public interface EmbeddingClient {
    boolean isEnabled();

    EmbeddingResult embedQuery(String text);

    EmbeddingResult embedDocuments(List<String> texts);

    /** 仅持久化资料批次使用：每次真实 HTTP 尝试前调用，拒绝时不得发送。 */
    default EmbeddingResult embedDocumentsWithBudget(List<String> texts, Runnable beforeRequest) {
        throw new RetrievalProviderException("EMBEDDING_REQUEST_BUDGET_UNSUPPORTED", "向量客户端不支持请求预算");
    }

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
