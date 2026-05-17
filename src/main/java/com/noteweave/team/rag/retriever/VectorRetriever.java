package com.noteweave.team.rag.retriever;

import com.noteweave.embedding.service.EmbeddingClient;
import com.noteweave.search.service.SearchChunkHit;
import com.noteweave.search.service.SearchIndexService;
import com.noteweave.team.document.model.Document;
import com.noteweave.team.document.model.DocumentChunk;
import com.noteweave.team.document.model.DocumentStatus;
import com.noteweave.team.document.repository.DocumentRepository;
import com.noteweave.team.document.service.DocumentChunkService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VectorRetriever implements Retriever {

    private final SearchIndexService searchIndexService;
    private final DocumentChunkService documentChunkService;
    private final DocumentRepository documentRepository;
    private final EmbeddingClient embeddingClient;

    @Override
    public String name() {
        return "Vector";
    }

    @Override
    public List<RetrievalHit> retrieve(TeamRetrievalQuery query) {
        if (query == null || query.knowledgeBaseIds() == null || query.knowledgeBaseIds().isEmpty()) {
            return List.of();
        }
        List<float[]> vectors = embeddingClient.embedTexts(List.of(query.query()));
        if (vectors.isEmpty()) {
            return List.of();
        }
        List<SearchChunkHit> hits = searchIndexService.searchChunkHitsByVector(
                query.spaceId(),
                query.knowledgeBaseIds(),
                vectors.get(0),
                query.topK()
        );
        if (hits.isEmpty()) {
            return List.of();
        }
        List<Long> orderedChunkIds = hits.stream().map(SearchChunkHit::chunkId).toList();
        Map<Long, SearchChunkHit> hitsByChunkId = hits.stream()
                .collect(Collectors.toMap(SearchChunkHit::chunkId, Function.identity(), (left, right) -> left));
        List<DocumentChunk> chunks = documentChunkService.findByIdsInOrder(orderedChunkIds);
        Map<Long, Document> documents = documentRepository.findAllById(
                        chunks.stream().map(DocumentChunk::getDocumentId).distinct().toList()
                )
                .stream()
                .collect(Collectors.toMap(Document::getId, Function.identity()));

        List<RetrievalHit> results = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            DocumentChunk chunk = chunks.get(index);
            Document document = documents.get(chunk.getDocumentId());
            SearchChunkHit hit = hitsByChunkId.get(chunk.getId());
            if (document == null
                    || hit == null
                    || document.getDeletedAt() != null
                    || document.getStatus() != DocumentStatus.INDEXED
                    || !document.getSpaceId().equals(query.spaceId())
                    || !query.knowledgeBaseIds().contains(document.getKnowledgeBaseId())
                    || document.getActiveIndexVersion() != chunk.getIndexVersion()) {
                continue;
            }
            results.add(RetrievalHit.builder()
                    .retrieverName(name())
                    .chunkId(chunk.getId())
                    .documentId(chunk.getDocumentId())
                    .knowledgeBaseId(chunk.getKnowledgeBaseId())
                    .spaceId(chunk.getSpaceId())
                    .chunkIndex(chunk.getChunkIndex())
                    .documentTitle(document.getTitle())
                    .content(chunk.getContent())
                    .score(hit.score() == null ? 1.0d : hit.score())
                    .rank(index + 1)
                    .metadata(Map.of("indexVersion", chunk.getIndexVersion()))
                    .build());
        }
        return results;
    }
}
