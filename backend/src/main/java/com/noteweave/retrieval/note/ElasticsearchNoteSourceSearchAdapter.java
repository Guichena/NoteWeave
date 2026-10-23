package com.noteweave.retrieval.note;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.noteweave.retrieval.RetrievalChannels;
import com.noteweave.retrieval.index.RetrievalIndexNames;
import com.noteweave.retrieval.projection.RetrievalProjectionRepository.ProjectionType;
import com.noteweave.retrieval.provider.RetrievalProviderException;
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
        return metadataRetrieve(workspaceId, RetrievalChannels.same(query), topK);
    }

    @Override
    public List<NoteSourceHit> metadataRetrieve(String workspaceId, RetrievalChannels channels, int topK) {
        RetrievalChannels safeChannels = channels == null ? RetrievalChannels.same("") : channels;
        String query = safeChannels.lexicalQuery();
        if (query.isBlank() || topK <= 0) {
            return List.of();
        }
        BoolQuery.Builder bool = new BoolQuery.Builder()
                .must(must -> must.multiMatch(multi -> multi
                        .query(query)
                        .fields("title^5", "tags^4", "summary^3", "section_descriptions^2", "metadata_text^2", "source_type")
                        .type(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.BestFields)
                        .fuzziness("AUTO")))
                .filter(scope(workspaceId));
        if (safeChannels.hasFieldBoosts()) {
            boostTerms(bool, safeChannels.mustTerms(),
                    "title^6", "tags^5", "summary^4", "section_descriptions^3", "metadata_text^3");
            boostTerms(bool, safeChannels.titleTerms(), "title^6", "tags^5");
            boostTerms(bool, safeChannels.bodyTerms(), "summary^4", "section_descriptions^4");
        }
        return search(new SearchRequest.Builder()
                .index(RetrievalIndexNames.alias(ProjectionType.NOTE_SOURCE, workspaceId))
                .size(topK)
                .query(queryBuilder -> queryBuilder.bool(bool.build()))
                .build());
    }

    private void boostTerms(BoolQuery.Builder bool, List<String> terms, String... fields) {
        if (terms == null || fields.length == 0) {
            return;
        }
        for (String term : terms) {
            if (term == null || term.isBlank()) {
                continue;
            }
            bool.should(should -> should.multiMatch(multi -> multi
                    .query(term)
                    .fields(List.of(fields))
                    .type(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.BestFields)));
        }
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
                if (source == null) {
                    throw invalidResponse("Note source Elasticsearch hit has no source document");
                }
                String sourceId = text(source.get("source_id"));
                String snapshotId = text(source.get("source_snapshot_id"));
                if (sourceId.isBlank() || snapshotId.isBlank()) {
                    throw invalidResponse("Note source Elasticsearch hit is missing an identity field");
                }
                result.add(new NoteSourceHit(
                        sourceId, snapshotId,
                        text(source.get("title")), text(source.get("source_type")),
                        text(source.get("summary")), strings(source.get("tags")),
                        text(source.get("metadata_text")), hit.score() == null ? 0.0d : hit.score()));
            }
            return result;
        } catch (ElasticsearchException ex) {
            if (ex.error() != null && "index_not_found_exception".equals(ex.error().type())) {
                return List.of();
            }
            throw new IllegalStateException("Note source Elasticsearch retrieval failed", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Note source Elasticsearch retrieval failed", ex);
        }
    }

    private RetrievalProviderException invalidResponse(String message) {
        return new RetrievalProviderException("NOTE_SOURCE_RETRIEVAL_RESPONSE_INVALID", message);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(this::text).filter(item -> !item.isBlank()).toList();
    }
}
