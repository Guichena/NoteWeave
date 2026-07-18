package com.noteweave.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.noteweave.config.NoteWeaveProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

class ElasticsearchChunkSearchAdapterTest {

    @Test
    void shouldMapWorkspaceScopedSearchResponseToPortHits() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        ObjectProvider<ElasticsearchClient> provider = provider(client);
        SearchResponse<Map> response = mock(SearchResponse.class);
        HitsMetadata<Map> metadata = mock(HitsMetadata.class);
        Hit<Map> hit = mock(Hit.class);
        when(client.search(any(java.util.function.Function.class), eq(Map.class)))
                .thenReturn(response);
        when(response.hits()).thenReturn(metadata);
        when(metadata.hits()).thenReturn(List.of(hit));
        when(hit.source()).thenReturn(Map.of(
                "chunk_id", "chunk-1",
                "source_id", "source-1",
                "source_snapshot_id", "snapshot-1",
                "chunk_no", 3,
                "title", "Title",
                "source_type", "MARKDOWN",
                "content", "Content"
        ));
        when(hit.score()).thenReturn(12.5d);
        ElasticsearchChunkSearchAdapter adapter = new ElasticsearchChunkSearchAdapter(
                provider, properties(true));

        var result = adapter.search("Workspace-A", "query", 12);

        assertThat(adapter.indexName("Workspace-A")).isEqualTo("noteweave_chunk_workspace-a");
        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.chunkId()).isEqualTo("chunk-1");
            assertThat(item.sourceId()).isEqualTo("source-1");
            assertThat(item.sourceSnapshotId()).isEqualTo("snapshot-1");
            assertThat(item.chunkNo()).isEqualTo("3");
            assertThat(item.title()).isEqualTo("Title");
            assertThat(item.sourceType()).isEqualTo("MARKDOWN");
            assertThat(item.content()).isEqualTo("Content");
            assertThat(item.score()).isEqualTo(12.5d);
        });
    }

    @Test
    void shouldReturnNoHitsOnlyWhenElasticsearchIsExplicitlyDisabled() {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        ObjectProvider<ElasticsearchClient> disabledProvider = provider(client);
        ElasticsearchChunkSearchAdapter disabled = new ElasticsearchChunkSearchAdapter(
                disabledProvider, properties(false));
        ObjectProvider<ElasticsearchClient> missingProvider = provider(null);
        ElasticsearchChunkSearchAdapter missing = new ElasticsearchChunkSearchAdapter(
                missingProvider, properties(true));

        assertThat(disabled.search("workspace", "query", 12)).isEmpty();
        assertThatThrownBy(() -> missing.search("workspace", "query", 12))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client is unavailable");
        assertThat(disabled.search("workspace", "query", 0)).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void shouldExposeElasticsearchFailureToTheCallingRetrievalPolicy() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        when(client.search(any(java.util.function.Function.class), eq(Map.class)))
                .thenThrow(new java.io.IOException("connection refused for sensitive-query"));
        ElasticsearchChunkSearchAdapter adapter = new ElasticsearchChunkSearchAdapter(
                provider(client), properties(true));
        Logger logger = (Logger) LoggerFactory.getLogger(ElasticsearchChunkSearchAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            assertThatThrownBy(() -> adapter.search("workspace-sensitive-id", "sensitive-query", 12))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("chunk search failed")
                    .hasCauseInstanceOf(java.io.IOException.class);

            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Elasticsearch chunk search failed; reason=es_search_failed")
                        .doesNotContain("workspace-sensitive-id", "sensitive-query", "connection refused");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<ElasticsearchClient> provider(ElasticsearchClient client) {
        ObjectProvider<ElasticsearchClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }

    private NoteWeaveProperties properties(boolean enabled) {
        return new NoteWeaveProperties(
                null,
                null,
                null,
                null,
                new NoteWeaveProperties.Elasticsearch(
                        enabled, "localhost", 9200, "http", "noteweave_chunk"),
                null
        );
    }
}
