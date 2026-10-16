package com.noteweave.retrieval.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.PutMappingRequest;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import co.elastic.clients.util.ObjectBuilder;
import com.noteweave.retrieval.projection.RetrievalProjectionRepository.ProjectionType;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class RetrievalIndexManagerMappingTest {
    private final RetrievalIndexManager manager = RetrievalIndexManager.withClient(null);

    @Test
    void contextTermsAreCopiedIntoBm25ContentWithoutChangingStoredContent() {
        var mapping = manager.mapping(ProjectionType.QA_CHUNK, 8);
        assertThat(mapping.properties().get("context_text").text().copyTo()).containsExactly("content");
        assertThat(mapping.properties().get("context_text").text().analyzer())
                .isEqualTo(RetrievalIndexManager.TEXT_ANALYZER);
        assertThat(manager.mapping(ProjectionType.NOTE_SOURCE, 8).properties()).doesNotContainKey("context_text");
    }

    @Test
    @SuppressWarnings("unchecked")
    void addsContextMappingToExistingQaIndexWithoutCreatingAnEmptyReplacement() throws Exception {
        var client = mock(ElasticsearchClient.class);
        var indices = mock(ElasticsearchIndicesClient.class);
        when(client.indices()).thenReturn(indices);
        when(indices.exists(any(Function.class))).thenReturn(new BooleanResponse(true));
        when(indices.putMapping(any(Function.class))).thenAnswer(invocation -> {
            Function<PutMappingRequest.Builder, ObjectBuilder<PutMappingRequest>> configure = invocation.getArgument(0);
            var request = configure.apply(new PutMappingRequest.Builder()).build();
            assertThat(request.index()).containsExactly("existing-qa");
            assertThat(request.properties().get("context_text").text().copyTo()).containsExactly("content");
            return null;
        });
        RetrievalIndexManager.withClient(client).createIndex(ProjectionType.QA_CHUNK, "existing-qa", 8);
        verify(indices).putMapping(any(Function.class));
        verify(indices, never()).create(any(Function.class));
    }

    @Test
    void qaMappingContainsIndexedCosineChunkVectorAndScopeFields() {
        TypeMapping mapping = manager.mapping(ProjectionType.QA_CHUNK, 1024);

        assertThat(mapping.properties()).containsKeys(
                "workspace_id", "source_id", "source_snapshot_id", "chunk_id",
                "content", "content_embedding", "is_current_snapshot");
        assertThat(mapping.properties().get("content_embedding").denseVector().dims()).isEqualTo(1024);
        assertThat(mapping.properties().get("content_embedding").denseVector().index()).isTrue();
        assertThat(mapping.properties().get("content_embedding").denseVector().similarity())
                .isEqualTo("cosine");
    }

    @Test
    void noteMappingUsesSourceLevelVectorAndStructuredMetadata() {
        TypeMapping mapping = manager.mapping(ProjectionType.NOTE_SOURCE, 768);

        assertThat(mapping.properties()).containsKeys(
                "workspace_id", "source_id", "source_snapshot_id", "summary", "tags",
                "metadata_text", "section_descriptions", "source_embedding");
        assertThat(mapping.properties()).doesNotContainKey("content_embedding");
        assertThat(mapping.properties().get("source_embedding").denseVector().dims()).isEqualTo(768);
    }

    @Test
    void textFieldsUseTheCjkBigramAnalyzer() {
        var analyzer = manager.settings().analysis().analyzer().get(RetrievalIndexManager.TEXT_ANALYZER);

        assertThat(analyzer.custom().tokenizer()).isEqualTo("standard");
        assertThat(analyzer.custom().filter()).containsExactly("cjk_width", "lowercase", "cjk_bigram");
        TypeMapping qa = manager.mapping(ProjectionType.QA_CHUNK, 1024);
        assertThat(qa.properties().get("content").text().analyzer()).isEqualTo(RetrievalIndexManager.TEXT_ANALYZER);
        assertThat(qa.properties().get("heading").text().analyzer()).isEqualTo(RetrievalIndexManager.TEXT_ANALYZER);
        TypeMapping note = manager.mapping(ProjectionType.NOTE_SOURCE, 768);
        assertThat(note.properties().get("summary").text().analyzer()).isEqualTo(RetrievalIndexManager.TEXT_ANALYZER);
    }
}
