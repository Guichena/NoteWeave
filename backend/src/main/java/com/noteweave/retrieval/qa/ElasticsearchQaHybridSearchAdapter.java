package com.noteweave.retrieval.qa;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.noteweave.retrieval.index.RetrievalIndexNames;
import com.noteweave.retrieval.projection.RetrievalProjectionRepository.ProjectionType;
import com.noteweave.retrieval.qa.QaHybridSearchPort.QaKeywordQuery;
import com.noteweave.retrieval.qa.QaHybridSearchPort.QaSearchHit;
import com.noteweave.retrieval.qa.QaHybridSearchPort.QaVectorQuery;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ElasticsearchQaHybridSearchAdapter implements QaHybridSearchPort {
    private final ElasticsearchClient client;

    public ElasticsearchQaHybridSearchAdapter(ElasticsearchClient client) {
        this.client = client;
    }

    @Override
    public List<QaSearchHit> vectorRetrieve(QaVectorQuery query) {
        if (query.topK() <= 0 || query.queryEmbedding().isEmpty()) {
            return List.of();
        }
        Query scope = scopeFilter(query.workspaceId(), query.sourceScope());
        KnnSearch knn = new KnnSearch.Builder()
                .field("content_embedding")
                .queryVector(query.queryEmbedding())
                .k(query.topK())
                .numCandidates(Math.max(query.topK() * 5, 100))
                .similarity(query.threshold() > 0 ? (float) query.threshold() : null)
                .filter(scope)
                .build();
        SearchRequest request = new SearchRequest.Builder()
                .index(RetrievalIndexNames.alias(ProjectionType.QA_CHUNK, query.workspaceId()))
                .size(query.topK())
                .knn(knn)
                .build();
        return search(request);
    }

    @Override
    public List<QaSearchHit> keywordRetrieve(QaKeywordQuery query) {
        if (query.topK() <= 0 || query.query() == null || query.query().isBlank()) {
            return List.of();
        }
        BoolQuery.Builder bool = new BoolQuery.Builder()
                .must(must -> must.multiMatch(multi -> multi
                        .query(query.query())
                        .fields("content^3", "title^2", "heading^2", "source_type")
                        .type(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.BestFields)
                        .fuzziness("AUTO")))
                .filter(scopeFilter(query.workspaceId(), query.sourceScope()));
        SearchRequest.Builder request = new SearchRequest.Builder()
                .index(RetrievalIndexNames.alias(ProjectionType.QA_CHUNK, query.workspaceId()))
                .size(query.topK())
                .query(q -> q.bool(bool.build()));
        if (query.threshold() > 0) {
            request.minScore(query.threshold());
        }
        return search(request.build());
    }

    Query scopeFilter(String workspaceId, Set<String> sourceScope) {
        BoolQuery.Builder bool = new BoolQuery.Builder()
                .filter(filter -> filter.term(term -> term.field("workspace_id").value(workspaceId)))
                .filter(filter -> filter.term(term -> term.field("is_current_snapshot").value(true)));
        if (sourceScope != null && !sourceScope.isEmpty()) {
            bool.filter(filter -> filter.terms(terms -> terms
                    .field("source_id")
                    .terms(values -> values.value(sourceScope.stream()
                            .sorted()
                            .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                            .toList()))));
        }
        return Query.of(query -> query.bool(bool.build()));
    }

    private List<QaSearchHit> search(SearchRequest request) {
        try {
            SearchResponse<Map> response = client.search(request, Map.class);
            List<QaSearchHit> hits = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                Map source = hit.source();
                if (source == null) {
                    continue;
                }
                hits.add(new QaSearchHit(
                        text(source.get("chunk_id")),
                        text(source.get("source_id")),
                        text(source.get("source_snapshot_id")),
                        integer(source.get("chunk_no")),
                        text(source.get("title")),
                        text(source.get("heading")),
                        text(source.get("source_type")),
                        text(source.get("content")),
                        hit.score() == null ? 0.0d : hit.score()));
            }
            return hits;
        } catch (IOException ex) {
            throw new IllegalStateException("QA Elasticsearch retrieval failed", ex);
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(text(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
