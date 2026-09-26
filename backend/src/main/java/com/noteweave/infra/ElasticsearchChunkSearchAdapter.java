package com.noteweave.infra;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.retrieval.provider.RetrievalProviderException;
import com.noteweave.search.ChunkSearchHit;
import com.noteweave.search.ChunkSearchPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class ElasticsearchChunkSearchAdapter implements ChunkSearchPort {
    private static final Logger log = LoggerFactory.getLogger(ElasticsearchChunkSearchAdapter.class);

    private final ObjectProvider<ElasticsearchClient> clientProvider;
    private final NoteWeaveProperties properties;

    public ElasticsearchChunkSearchAdapter(
            ObjectProvider<ElasticsearchClient> clientProvider,
            NoteWeaveProperties properties
    ) {
        this.clientProvider = clientProvider;
        this.properties = properties;
    }

    @Override
    public List<ChunkSearchHit> search(String workspaceId, String query, int topK) {
        if (topK <= 0) {
            return List.of();
        }
        if (!properties.elasticsearch().enabled()) {
            throw new RetrievalProviderException(
                    "QA_CHUNK_SEARCH_PROVIDER_DISABLED",
                    "Elasticsearch chunk search provider is disabled");
        }
        ElasticsearchClient client = clientProvider.getIfAvailable();
        if (client == null) {
            log.warn("Elasticsearch search requested without an available client");
            throw new IllegalStateException("Elasticsearch search client is unavailable");
        }
        try {
            SearchResponse<Map> response = client.search(s -> s
                    .index(indexName(workspaceId))
                    .size(topK)
                    .query(q -> q.multiMatch(m -> m
                            .query(query)
                            .fields("content^3", "title^2")
                            .type(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.BestFields)
                            .fuzziness("AUTO"))), Map.class);

            List<ChunkSearchHit> hits = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                Map source = hit.source();
                if (source == null) {
                    throw invalidResponse("Elasticsearch chunk hit has no source document");
                }
                String chunkId = text(source.get("chunk_id"));
                String sourceId = text(source.get("source_id"));
                String sourceSnapshotId = text(source.get("source_snapshot_id"));
                String chunkNo = text(source.get("chunk_no"));
                String content = text(source.get("content"));
                if (chunkId.isBlank() || sourceId.isBlank() || sourceSnapshotId.isBlank()
                        || chunkNo.isBlank() || content.isBlank()) {
                    throw invalidResponse("Elasticsearch chunk hit is missing an identity or content field");
                }
                hits.add(new ChunkSearchHit(
                        chunkId,
                        sourceId,
                        sourceSnapshotId,
                        chunkNo,
                        text(source.get("title")),
                        text(source.get("source_type")),
                        content,
                        hit.score() == null ? 0.0d : hit.score()
                ));
            }
            return hits;
        } catch (RetrievalProviderException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Elasticsearch chunk search failed; reason=es_search_failed");
            throw new IllegalStateException("Elasticsearch chunk search failed", ex);
        }
    }

    private RetrievalProviderException invalidResponse(String message) {
        return new RetrievalProviderException("QA_CHUNK_SEARCH_RESPONSE_INVALID", message);
    }

    String indexName(String workspaceId) {
        return (properties.elasticsearch().indexPrefix() + "_" + workspaceId).toLowerCase();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
