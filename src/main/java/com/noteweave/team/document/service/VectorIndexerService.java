package com.noteweave.team.document.service;

import com.noteweave.embedding.config.EmbeddingProperties;
import com.noteweave.embedding.service.EmbeddingClient;
import com.noteweave.search.service.SearchIndexService;
import com.noteweave.team.document.model.Document;
import com.noteweave.team.document.model.DocumentChunk;
import com.noteweave.team.document.model.DocumentStatus;
import com.noteweave.team.document.repository.DocumentChunkRepository;
import com.noteweave.team.document.repository.DocumentRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class VectorIndexerService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final SearchIndexService searchIndexService;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingProperties embeddingProperties;

    public VectorIndexVersion ensureVersion(String model, int dimension) {
        String safeModel = model == null || model.isBlank() ? "default" : model.trim().replaceAll("[^a-zA-Z0-9._-]", "-");
        int safeDimension = Math.max(1, dimension);
        return VectorIndexVersion.builder()
                .aliasName(searchIndexService.documentChunkVectorAliasName())
                .indexName(searchIndexService.documentChunkVectorIndexName(safeModel, safeDimension))
                .model(safeModel)
                .dimension(safeDimension)
                .build();
    }

    public void switchAliasWhenReady(VectorIndexVersion version) {
        if (version == null) {
            return;
        }
        searchIndexService.switchVectorAlias(version.aliasName(), version.indexName());
    }

    public void markBackfillFailed(Long documentId, String reason) {
        if (documentId == null) {
            return;
        }
        log.warn("Embedding backfill failed for document {}: {}", documentId, reason);
    }

    @Transactional
    public int backfillDocumentEmbeddings(Long documentId, Long knowledgeBaseId) {
        if (documentId == null) {
            return 0;
        }
        Document document = documentRepository.findById(documentId).orElse(null);
        if (document == null
                || document.getDeletedAt() != null
                || document.getStatus() != DocumentStatus.INDEXED
                || (knowledgeBaseId != null && !knowledgeBaseId.equals(document.getKnowledgeBaseId()))
                || document.getActiveIndexVersion() <= 0) {
            return 0;
        }
        List<DocumentChunk> chunks = documentChunkRepository.findByDocumentIdAndIndexVersionOrderByChunkIndexAsc(
                documentId,
                document.getActiveIndexVersion()
        );
        if (chunks.isEmpty()) {
            return 0;
        }
        VectorIndexVersion version = ensureVersion(
                embeddingProperties.api().model(),
                embeddingProperties.api().dimension()
        );
        searchIndexService.ensureVectorIndex(version.indexName(), version.dimension());
        List<float[]> vectors = embeddingClient.embedTexts(chunks.stream().map(DocumentChunk::getContent).toList());
        searchIndexService.bulkIndexChunkEmbeddings(version.indexName(), chunks, vectors);
        switchAliasWhenReady(version);
        return chunks.size();
    }
}
