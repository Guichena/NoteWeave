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
}
