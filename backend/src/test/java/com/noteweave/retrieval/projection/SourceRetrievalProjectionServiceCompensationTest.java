package com.noteweave.retrieval.projection;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.retrieval.index.RetrievalIndexManager;
import com.noteweave.retrieval.index.RetrievalProjectionWriter;
import com.noteweave.retrieval.provider.EmbeddingClient;
import com.noteweave.retrieval.provider.RetrievalProviderException;
import com.noteweave.source.SourceTagCodec;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SourceRetrievalProjectionServiceCompensationTest {

    @Test
    void projectionFailureShouldDeactivateAnyPartiallyWrittenSnapshotDocuments() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        RetrievalProjectionWriter projectionWriter = mock(RetrievalProjectionWriter.class);
        RetrievalIndexManager indexManager = mock(RetrievalIndexManager.class);
        NoteWeaveProperties properties = mock(NoteWeaveProperties.class);
        NoteWeaveProperties.Embedding embedding = mock(NoteWeaveProperties.Embedding.class);
        when(properties.embedding()).thenReturn(embedding);
        when(embedding.model()).thenReturn("embedding-v1");
        when(embedding.dimensions()).thenReturn(1024);
        when(embeddingClient.isEnabled()).thenReturn(false);
        when(indexManager.resolveWriteIndex(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        SourceRetrievalProjectionService service = new SourceRetrievalProjectionService(
                mock(JdbcTemplate.class),
                embeddingClient,
                mock(RetrievalProjectionRepository.class),
                indexManager,
                projectionWriter,
                properties,
                new SourceTagCodec(new ObjectMapper())
        );

        assertThatThrownBy(() -> service.projectSnapshot(
                "workspace-1", "source-1", "snapshot-1"))
                .isInstanceOf(RetrievalProviderException.class)
                .hasMessageContaining("requires embedding");

        verify(projectionWriter, times(2))
                .markSnapshotNotCurrent(anyString(), eq("snapshot-1"));
    }
}
