package com.noteweave.retrieval.qa;

import java.util.List;
import java.util.Set;

public interface QaHybridSearchPort {
    List<QaSearchHit> vectorRetrieve(QaVectorQuery query);

    List<QaSearchHit> keywordRetrieve(QaKeywordQuery query);

    record QaVectorQuery(
            String workspaceId,
            String query,
            List<Float> queryEmbedding,
            Set<String> sourceScope,
            int topK,
            double threshold
    ) {
        public QaVectorQuery {
            queryEmbedding = queryEmbedding == null ? List.of() : List.copyOf(queryEmbedding);
            sourceScope = sourceScope == null ? Set.of() : Set.copyOf(sourceScope);
        }
    }

    record QaKeywordQuery(
            String workspaceId,
            String query,
            Set<String> sourceScope,
            int topK,
            double threshold,
            List<String> mustTerms,
            List<String> titleTerms,
            List<String> bodyTerms
    ) {
        public QaKeywordQuery(
                String workspaceId,
                String query,
                Set<String> sourceScope,
                int topK,
                double threshold
        ) {
            this(workspaceId, query, sourceScope, topK, threshold, List.of(), List.of(), List.of());
        }

        public QaKeywordQuery {
            sourceScope = sourceScope == null ? Set.of() : Set.copyOf(sourceScope);
            mustTerms = mustTerms == null ? List.of() : List.copyOf(mustTerms);
            titleTerms = titleTerms == null ? List.of() : List.copyOf(titleTerms);
            bodyTerms = bodyTerms == null ? List.of() : List.copyOf(bodyTerms);
        }
    }

    record QaSearchHit(
            String chunkId,
            String sourceId,
            String sourceSnapshotId,
            int chunkNo,
            String title,
            String heading,
            String sourceType,
            String content,
            double score
    ) {
    }
}
