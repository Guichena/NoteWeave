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
            double threshold
    ) {
        public QaKeywordQuery {
            sourceScope = sourceScope == null ? Set.of() : Set.copyOf(sourceScope);
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
