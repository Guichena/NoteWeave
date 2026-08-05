package com.noteweave.retrieval.note;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.noteweave.retrieval.index.RetrievalIndexNames;
import com.noteweave.retrieval.projection.RetrievalProjectionRepository.ProjectionType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "noteweave.elasticsearch.enabled", havingValue = "true", matchIfMissing = true)
public class ElasticsearchNoteSourceSearchAdapter implements NoteSourceSearchPort {
    private final ElasticsearchClient client;

    public ElasticsearchNoteSourceSearchAdapter(ElasticsearchClient client) {
        this.client = client;
    }

    @Override
    public List<NoteSourceHit> semanticRetrieve(
            String workspaceId, List<Float> queryEmbedding, int topK
    ) {
        if (queryEmbedding == null || queryEmbedding.isEmpty() || topK <= 0) {
            return List.of();
        }
        KnnSearch knn = new KnnSearch.Builder()
                .field("source_embedding")
                .queryVector(queryEmbedding)
                .k(topK)
                .numCandidates(Math.max(100, topK * 5))
                .filter(scope(workspaceId))
                .build();
        return search(new SearchRequest.Builder()
                .index(RetrievalIndexNames.alias(ProjectionType.NOTE_SOURCE, workspaceId))
                .size(topK)
                .knn(knn)
                .build());
    }

    @Override
    public List<NoteSourceHit> metadataRetrieve(String workspaceId, String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        BoolQuery bool = new BoolQuery.Builder()
                .must(must -> must.multiMatch(multi -> multi
                        .query(query)
                        .fields("title^5", "tags^4", "summary^3", "section_descriptions^2", "metadata_text^2", "source_type")
                        .type(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.BestFields)
                        .fuzziness("AUTO")))
                .filter(scope(workspaceId))
                .build();
        return search(new SearchRequest.Builder()
                .index(RetrievalIndexNames.alias(ProjectionType.NOTE_SOURCE, workspaceId))
                .size(topK)
                .query(queryBuilder -> queryBuilder.bool(bool))
                .build());
    }

    private Query scope(String workspaceId) {
        return Query.of(query -> query.bool(bool -> bool
                .filter(filter -> filter.term(term -> term.field("workspace_id").value(workspaceId)))
                .filter(filter -> filter.term(term -> term.field("is_current_snapshot").value(true)))));
    }

    @SuppressWarnings("rawtypes")
    private List<NoteSourceHit> search(SearchRequest request) {
        try {
            SearchResponse<Map> response = client.search(request, Map.class);
            List<NoteSourceHit> result = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                Map source = hit.source();
                if (source == null) continue;
                result.add(new NoteSourceHit(
                        text(source.get("source_id")), text(source.get("source_snapshot_id")),
                        text(source.get("title")), text(source.get("source_type")),
                        text(source.get("summary")), strings(source.get("tags")),
                        text(source.get("metadata_text")), hit.score() == null ? 0.0d : hit.score()));
            }
            return result;
        } catch (IOException ex) {
            throw new IllegalStateException("Note source Elasticsearch retrieval failed", ex);
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(this::text).filter(item -> !item.isBlank()).toList();
    }
}
