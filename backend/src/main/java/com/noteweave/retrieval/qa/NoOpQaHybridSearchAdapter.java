package com.noteweave.retrieval.qa;

import java.util.List;

/** Empty search adapter used when Elasticsearch is disabled. */
public class NoOpQaHybridSearchAdapter implements QaHybridSearchPort {

    @Override
    public List<QaSearchHit> vectorRetrieve(QaVectorQuery query) {
        return List.of();
    }

    @Override
    public List<QaSearchHit> keywordRetrieve(QaKeywordQuery query) {
        return List.of();
    }
}
