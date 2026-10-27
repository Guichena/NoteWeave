package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** IDX-03 的同型缺陷：记忆索引的墓碑写入同样必须先按预期映射建索引。 */
class ElasticsearchMemoryVectorIndexMappingTest {

    private static final int DIMENSIONS = 4;
    private static final String INDEX = "noteweave-memory";

    @Test
    void createdMappingPassesItsOwnVerification() {
        ElasticsearchMemoryVectorIndex index = new ElasticsearchMemoryVectorIndex(null, INDEX, DIMENSIONS);

        assertThat(ElasticsearchMemoryVectorIndex.mappingProblem(index.mapping(), DIMENSIONS)).isNull();
    }

    @Test
    void rejectsAnIndexCreatedByDynamicMapping() {
        TypeMapping dynamic = new TypeMapping.Builder().properties(Map.of(
                "workspace_id", Property.of(property -> property.text(text -> text)),
                "deleted", Property.of(property -> property.boolean_(bool -> bool)),
                "embedding", Property.of(property -> property.float_(number -> number)))).build();

        assertThat(ElasticsearchMemoryVectorIndex.mappingProblem(dynamic, DIMENSIONS))
                .contains("workspace_id");
    }

    @Test
    void rejectsAnIndexWhoseVectorDimensionsChanged() {
        ElasticsearchMemoryVectorIndex index = new ElasticsearchMemoryVectorIndex(null, INDEX, DIMENSIONS);

        assertThat(ElasticsearchMemoryVectorIndex.mappingProblem(index.mapping(), 8))
                .contains("维度");
    }

    @Test
    void firstTombstoneCreatesTheIndexBeforeWriting() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);
        when(client.indices()).thenReturn(indices);
        when(indices.exists(any(Function.class))).thenReturn(new BooleanResponse(false));
        ElasticsearchMemoryVectorIndex index =
                new ElasticsearchMemoryVectorIndex(client, INDEX, DIMENSIONS);

        assertThat(index.delete("workspace-1", "memory-1", 2L)).isTrue();

        InOrder order = inOrder(indices, client);
        order.verify(indices).create(any(Function.class));
        order.verify(client).index(any(Function.class));
    }
}
