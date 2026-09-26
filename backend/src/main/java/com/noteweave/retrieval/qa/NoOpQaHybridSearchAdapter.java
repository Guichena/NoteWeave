package com.noteweave.retrieval.qa;

import com.noteweave.retrieval.provider.RetrievalProviderException;
import java.util.List;

/** Failing search adapter used when Elasticsearch is disabled. */
public class NoOpQaHybridSearchAdapter implements QaHybridSearchPort {

    @Override
    public List<QaSearchHit> vectorRetrieve(QaVectorQuery query) {
        throw disabled();
    }

    @Override
    public List<QaSearchHit> keywordRetrieve(QaKeywordQuery query) {
        throw disabled();
    }

    private RetrievalProviderException disabled() {
        return new RetrievalProviderException(
                "QA_SEARCH_PROVIDER_DISABLED",
                "QA Elasticsearch search provider is disabled"
        );
    }
}
