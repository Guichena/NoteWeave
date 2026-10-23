package com.noteweave.retrieval.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import co.elastic.clients.util.ObjectBuilder;
import com.noteweave.retrieval.projection.RetrievalProjectionRepository.ProjectionType;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** 校验检索索引创建时的副本数设置，test-es profile 下必须为 0。 */
class RetrievalIndexManagerReplicaSettingsTest {

    @Test
    void testEsProfileCreatesIndexWithoutReplicas() throws Exception {
        assertThat(captureCreateRequest(0).settings().numberOfReplicas()).isEqualTo("0");
    }

    @Test
    void defaultProfileKeepsSingleReplica() throws Exception {
        assertThat(captureCreateRequest(1).settings().numberOfReplicas()).isEqualTo("1");
    }

    @SuppressWarnings("unchecked")
    private CreateIndexRequest captureCreateRequest(int replicas) throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);
        when(client.indices()).thenReturn(indices);
        when(indices.exists(any(Function.class))).thenReturn(new BooleanResponse(false));
        AtomicReference<CreateIndexRequest> captured = new AtomicReference<>();
        when(indices.create(any(Function.class))).thenAnswer(invocation -> {
            Function<CreateIndexRequest.Builder, ObjectBuilder<CreateIndexRequest>> configure =
                    invocation.getArgument(0);
            captured.set(configure.apply(new CreateIndexRequest.Builder()).build());
            return null;
        });
        RetrievalIndexManager.withClient(client, replicas)
                .createIndex(ProjectionType.QA_CHUNK, "noteweave_chunk_qa_v1", 8);
        assertThat(captured.get()).isNotNull();
        return captured.get();
    }
}
