package com.noteweave.retrieval.qa;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ElasticsearchQaHybridSearchAdapterQueryTest {
    private final ElasticsearchQaHybridSearchAdapter adapter =
            new ElasticsearchQaHybridSearchAdapter(null);

    @Test
    void scopeFilterAppliesWorkspaceCurrentSnapshotAndSourceScopeBeforeRecall() {
        Query query = adapter.scopeFilter("workspace-1", Set.of("source-b", "source-a"));

        assertThat(query.isBool()).isTrue();
        assertThat(query.bool().filter()).hasSize(3);
        assertThat(query.bool().filter().get(0).term().field()).isEqualTo("workspace_id");
        assertThat(query.bool().filter().get(1).term().field()).isEqualTo("is_current_snapshot");
        assertThat(query.bool().filter().get(2).terms().field()).isEqualTo("source_id");
        assertThat(query.bool().filter().get(2).terms().terms().value())
                .extracting(value -> value.stringValue())
                .containsExactly("source-a", "source-b");
    }

    @Test
    void vectorScopeFiltersByTheCurrentEmbeddingVersion() {
        Query query = adapter.vectorScope("workspace-1", Set.of(), "embedding-batch-v5:1024:abc");

        assertThat(query.isBool()).isTrue();
        assertThat(query.bool().filter()).hasSize(2);
        Query versionFilter = query.bool().filter().get(1);
        assertThat(versionFilter.isTerm()).isTrue();
        assertThat(versionFilter.term().field()).isEqualTo("embedding_version");
        assertThat(versionFilter.term().value().stringValue()).isEqualTo("embedding-batch-v5:1024:abc");
    }

    @Test
    void vectorScopeWithoutAVersionKeepsThePlainScope() {
        Query query = adapter.vectorScope("workspace-1", Set.of("source-a"), "");

        assertThat(query.bool().filter()).hasSize(1);
        assertThat(query.bool().filter().get(0).bool().filter()).hasSize(3);
    }
}
