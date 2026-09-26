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
import com.noteweave.retrieval.provider.RetrievalProviderException;
import com.noteweave.retrieval.qa.QaHybridSearchPort.QaKeywordQuery;
import com.noteweave.retrieval.qa.QaHybridSearchPort.QaSearchHit;
import com.noteweave.retrieval.qa.QaHybridSearchPort.QaVectorQuery;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "noteweave.elasticsearch.enabled", havingValue = "true", matchIfMissing = true)
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
                        .fields("title^3", "heading^3", "content^2")
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
                    throw invalidResponse("QA Elasticsearch hit has no source document");
                }
                String chunkId = text(source.get("chunk_id"));
                String sourceId = text(source.get("source_id"));
                String snapshotId = text(source.get("source_snapshot_id"));
                String content = text(source.get("content"));
                if (chunkId.isBlank() || sourceId.isBlank() || snapshotId.isBlank() || content.isBlank()) {
                    throw invalidResponse("QA Elasticsearch hit is missing an identity or content field");
                }
                hits.add(new QaSearchHit(
                        chunkId,
                        sourceId,
                        snapshotId,
                        integer(source.get("chunk_no")),
                        text(source.get("title")),
                        text(source.get("heading")),
                        text(source.get("source_type")),
                        content,
                        hit.score() == null ? 0.0d : hit.score()));
            }
            return hits;
        } catch (IOException ex) {
            throw new IllegalStateException("QA Elasticsearch retrieval failed", ex);
        }
    }

    private RetrievalProviderException invalidResponse(String message) {
        return new RetrievalProviderException("QA_RETRIEVAL_RESPONSE_INVALID", message);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int integer(Object value) {
        if (value instanceof Number number) {
            long candidate = number.longValue();
            if ((number instanceof Float || number instanceof Double)
                    && number.doubleValue() != candidate) {
                throw invalidResponse("QA Elasticsearch hit has an invalid chunk_no field");
            }
            if (candidate < Integer.MIN_VALUE || candidate > Integer.MAX_VALUE) {
                throw invalidResponse("QA Elasticsearch hit has an invalid chunk_no field");
            }
            return (int) candidate;
        }
        try {
            return Integer.parseInt(text(value));
        } catch (NumberFormatException ex) {
            throw invalidResponse("QA Elasticsearch hit has an invalid chunk_no field");
        }
    }
}
