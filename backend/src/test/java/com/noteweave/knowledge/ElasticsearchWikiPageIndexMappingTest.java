package com.noteweave.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.GetMappingResponse;
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import co.elastic.clients.util.ObjectBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * IDX-03：墓碑写入是写路径，必须先按预期映射建索引；
 * 已存在的索引要校验 cjk 分词、keyword 作用域字段与 dense_vector 维度。
 */
class ElasticsearchWikiPageIndexMappingTest {

    private static final int DIMENSIONS = 4;
    private static final String INDEX = "noteweave-wiki";

    @Test
    void createdMappingPassesItsOwnVerification() {
        ElasticsearchWikiPageIndex index = new ElasticsearchWikiPageIndex(null, INDEX, DIMENSIONS);

        assertThat(ElasticsearchWikiPageIndex.mappingProblem(index.mapping(), DIMENSIONS)).isNull();
    }

    @Test
    void rejectsAnIndexCreatedByDynamicMapping() {
        TypeMapping dynamic = new TypeMapping.Builder().properties(Map.of(
                "workspace_id", Property.of(property -> property.text(text -> text)),
                "title", Property.of(property -> property.text(text -> text)),
                "deleted", Property.of(property -> property.boolean_(bool -> bool)),
                "page_embedding", Property.of(property -> property.float_(number -> number)))).build();

        assertThat(ElasticsearchWikiPageIndex.mappingProblem(dynamic, DIMENSIONS))
                .contains("workspace_id");
    }

    @Test
    void rejectsAnIndexWhoseEmbeddingIsNotDenseVector() {
        Map<String, Property> properties = new LinkedHashMap<>(keywordAndTextFields());
        properties.put("page_embedding", Property.of(property -> property.float_(number -> number)));

        assertThat(ElasticsearchWikiPageIndex.mappingProblem(
                new TypeMapping.Builder().properties(properties).build(), DIMENSIONS))
                .contains("page_embedding");
    }

    @Test
    void rejectsAnIndexWhoseVectorDimensionsChanged() {
        ElasticsearchWikiPageIndex index = new ElasticsearchWikiPageIndex(null, INDEX, DIMENSIONS);

        assertThat(ElasticsearchWikiPageIndex.mappingProblem(index.mapping(), 8))
                .contains("维度");
    }

    @Test
    void rejectsAnIndexThatDoesNotUseTheCjkAnalyzer() {
        Map<String, Property> properties = new LinkedHashMap<>(keywordAndTextFields());
        properties.put("content", Property.of(property -> property.text(text -> text.analyzer("standard"))));
        properties.put("page_embedding", denseVector());

        assertThat(ElasticsearchWikiPageIndex.mappingProblem(
                new TypeMapping.Builder().properties(properties).build(), DIMENSIONS))
                .contains("content");
    }

    @Test
    void firstTombstoneCreatesTheIndexBeforeWriting() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);
        when(client.indices()).thenReturn(indices);
        when(indices.exists(any(Function.class))).thenReturn(new BooleanResponse(false));
        AtomicReference<CreateIndexRequest> created = new AtomicReference<>();
        when(indices.create(any(Function.class))).thenAnswer(invocation -> {
            Function<CreateIndexRequest.Builder, ObjectBuilder<CreateIndexRequest>> configure =
                    invocation.getArgument(0);
            created.set(configure.apply(new CreateIndexRequest.Builder()).build());
            return null;
        });
        ElasticsearchWikiPageIndex index = new ElasticsearchWikiPageIndex(client, INDEX, DIMENSIONS);

        assertThat(index.delete("workspace-1", "item-1", 3L)).isTrue();

        assertThat(created.get()).isNotNull();
        assertThat(ElasticsearchWikiPageIndex.mappingProblem(created.get().mappings(), DIMENSIONS)).isNull();
        InOrder order = inOrder(indices, client);
        order.verify(indices).create(any(Function.class));
        order.verify(client).index(any(Function.class));
    }

    @Test
    void physicalDeleteCreatesTheIndexBeforeDeleting() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);
        when(client.indices()).thenReturn(indices);
        when(indices.exists(any(Function.class))).thenReturn(new BooleanResponse(false));
        ElasticsearchWikiPageIndex index = new ElasticsearchWikiPageIndex(client, INDEX, DIMENSIONS);

        assertThat(index.delete("workspace-1", "item-1")).isTrue();

        InOrder order = inOrder(indices, client);
        order.verify(indices).create(any(Function.class));
        order.verify(client).delete(any(Function.class));
    }

    @Test
    void refusesToWriteIntoAnIndexWithTheWrongMapping() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);
        when(client.indices()).thenReturn(indices);
        when(indices.exists(any(Function.class))).thenReturn(new BooleanResponse(true));
        TypeMapping dynamic = new TypeMapping.Builder().properties(Map.of(
                "workspace_id", Property.of(property -> property.text(text -> text)),
                "page_embedding", Property.of(property -> property.float_(number -> number)))).build();
        when(indices.getMapping(any(Function.class))).thenReturn(GetMappingResponse.of(response ->
                response.result(Map.of(INDEX, IndexMappingRecord.of(record -> record.mappings(dynamic))))));
        ElasticsearchWikiPageIndex index = new ElasticsearchWikiPageIndex(client, INDEX, DIMENSIONS);

        assertThat(index.upsert(document())).isFalse();
        verify(client, never()).index(any(Function.class));
    }

    private static Map<String, Property> keywordAndTextFields() {
        Map<String, Property> properties = new LinkedHashMap<>();
        properties.put("workspace_id", Property.of(property -> property.keyword(keyword -> keyword)));
        properties.put("title", Property.of(property -> property.text(text -> text.analyzer("cjk"))));
        properties.put("summary", Property.of(property -> property.text(text -> text.analyzer("cjk"))));
        properties.put("content", Property.of(property -> property.text(text -> text.analyzer("cjk"))));
        properties.put("deleted", Property.of(property -> property.boolean_(bool -> bool)));
        return properties;
    }

    private static Property denseVector() {
        return Property.of(property -> property.denseVector(vector -> vector
                .dims(DIMENSIONS)
                .index(true)
                .similarity("cosine")));
    }

    private static WikiPageIndex.Document document() {
        return new WikiPageIndex.Document(
                "workspace-1", "item-1", "version-1", 1, "CONCEPT", "标题", "摘要", "正文",
                0, 0, 0, 0, 1_700_000_000_000L, List.of(1f, 0f, 0f, 0f));
    }
}
