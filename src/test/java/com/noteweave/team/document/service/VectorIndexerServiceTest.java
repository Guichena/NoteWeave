package com.noteweave.team.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import com.noteweave.embedding.config.EmbeddingProperties;
import com.noteweave.embedding.service.EmbeddingClient;
import com.noteweave.search.service.SearchIndexService;
import com.noteweave.team.document.model.Document;
import com.noteweave.team.document.model.DocumentChunk;
import com.noteweave.team.document.model.DocumentStatus;
import com.noteweave.team.document.repository.DocumentChunkRepository;
import com.noteweave.team.document.repository.DocumentRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VectorIndexerServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentChunkRepository documentChunkRepository;

    @Mock
    private SearchIndexService searchIndexService;

    @Mock
    private EmbeddingClient embeddingClient;

    private VectorIndexerService vectorIndexerService;

    @BeforeEach
    void setUp() {
        vectorIndexerService = new VectorIndexerService(
                documentRepository,
                documentChunkRepository,
                searchIndexService,
                embeddingClient,
                new EmbeddingProperties(
                        true,
                        new EmbeddingProperties.Stub(true),
                        new EmbeddingProperties.Api("http://localhost", "test", "text-embedding-3-small", 8, 16)
                )
        );
    }

    @Test
    void shouldSwitchAliasOnlyAfterEmbeddingsAreIndexed() {
        Document document = new Document();
        document.setId(11L);
        document.setKnowledgeBaseId(22L);
        document.setStatus(DocumentStatus.INDEXED);
        document.setActiveIndexVersion(3);

        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(101L);
        chunk.setDocumentId(11L);
        chunk.setKnowledgeBaseId(22L);
        chunk.setSpaceId(33L);
        chunk.setIndexVersion(3);
        chunk.setChunkIndex(0);
        chunk.setContent("chunk body");
        chunk.setContentHash("hash");
        chunk.setEsDocId("doc-11-3-0");
        float[] vector = new float[]{0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f};

        when(documentRepository.findById(11L)).thenReturn(java.util.Optional.of(document));
        when(documentChunkRepository.findByDocumentIdAndIndexVersionOrderByChunkIndexAsc(11L, 3)).thenReturn(List.of(chunk));
        when(searchIndexService.documentChunkVectorAliasName()).thenReturn("noteweave-dev-document-chunk-vector");
        when(searchIndexService.documentChunkVectorIndexName("text-embedding-3-small", 8))
                .thenReturn("noteweave-dev-document-chunk-vector-text-embedding-3-small-8");
        when(embeddingClient.embedTexts(List.of("chunk body"))).thenReturn(List.of(vector));

        int backfilled = vectorIndexerService.backfillDocumentEmbeddings(11L, 22L);

        assertThat(backfilled).isEqualTo(1);
        InOrder inOrder = inOrder(searchIndexService, embeddingClient);
        inOrder.verify(searchIndexService).ensureVectorIndex("noteweave-dev-document-chunk-vector-text-embedding-3-small-8", 8);
        inOrder.verify(embeddingClient).embedTexts(List.of("chunk body"));
        inOrder.verify(searchIndexService).bulkIndexChunkEmbeddings("noteweave-dev-document-chunk-vector-text-embedding-3-small-8", List.of(chunk), List.of(vector));
        inOrder.verify(searchIndexService).switchVectorAlias("noteweave-dev-document-chunk-vector", "noteweave-dev-document-chunk-vector-text-embedding-3-small-8");
    }
}
