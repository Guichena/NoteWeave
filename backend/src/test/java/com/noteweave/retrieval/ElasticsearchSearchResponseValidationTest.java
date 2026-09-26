package com.noteweave.retrieval;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import com.noteweave.retrieval.note.ElasticsearchNoteSourceSearchAdapter;
import com.noteweave.retrieval.provider.RetrievalProviderException;
import com.noteweave.retrieval.qa.ElasticsearchQaHybridSearchAdapter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ElasticsearchSearchResponseValidationTest {

    @Test
    void qaSearchMustRejectAHitWithoutCanonicalIdentity() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        SearchResponse<Map> response = response(Map.of("source_id", "source-1", "content", "text"));
        when(client.search(any(SearchRequest.class), eq(Map.class)))
                .thenReturn(response);

        assertThatThrownBy(() -> new ElasticsearchQaHybridSearchAdapter(client).keywordRetrieve(
                new com.noteweave.retrieval.qa.QaHybridSearchPort.QaKeywordQuery(
                        "workspace-1", "query", java.util.Set.of(), 5, 0)))
                .isInstanceOfSatisfying(RetrievalProviderException.class, error ->
                        org.assertj.core.api.Assertions.assertThat(error.errorCode())
                                .isEqualTo("QA_RETRIEVAL_RESPONSE_INVALID"));
    }

    @Test
    void noteSearchMustRejectAHitWithoutSnapshotIdentity() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        SearchResponse<Map> response = response(Map.of("source_id", "source-1"));
        when(client.search(any(SearchRequest.class), eq(Map.class)))
                .thenReturn(response);

        assertThatThrownBy(() -> new ElasticsearchNoteSourceSearchAdapter(client).metadataRetrieve(
                "workspace-1", "query", 5))
                .isInstanceOfSatisfying(RetrievalProviderException.class, error ->
                        org.assertj.core.api.Assertions.assertThat(error.errorCode())
                                .isEqualTo("NOTE_SOURCE_RETRIEVAL_RESPONSE_INVALID"));
    }

    @Test
    void qaSearchMustRejectAnInvalidChunkNumberInsteadOfDefaultingToZero() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        SearchResponse<Map> response = response(Map.of(
                "chunk_id", "chunk-1",
                "source_id", "source-1",
                "source_snapshot_id", "snapshot-1",
                "chunk_no", "not-a-number",
                "content", "text"));
        when(client.search(any(SearchRequest.class), eq(Map.class))).thenReturn(response);

        assertThatThrownBy(() -> new ElasticsearchQaHybridSearchAdapter(client).keywordRetrieve(
                new com.noteweave.retrieval.qa.QaHybridSearchPort.QaKeywordQuery(
                        "workspace-1", "query", java.util.Set.of(), 5, 0)))
                .isInstanceOfSatisfying(RetrievalProviderException.class, error ->
                        org.assertj.core.api.Assertions.assertThat(error.errorCode())
                                .isEqualTo("QA_RETRIEVAL_RESPONSE_INVALID"));
    }

    @SuppressWarnings("unchecked")
    private SearchResponse<Map> response(Map source) {
        SearchResponse<Map> response = mock(SearchResponse.class);
        HitsMetadata<Map> metadata = mock(HitsMetadata.class);
        Hit<Map> hit = mock(Hit.class);
        when(response.hits()).thenReturn(metadata);
        when(metadata.hits()).thenReturn(List.of(hit));
        when(hit.source()).thenReturn(source);
        return response;
    }
}
