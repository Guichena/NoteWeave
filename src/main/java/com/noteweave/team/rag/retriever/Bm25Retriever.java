package com.noteweave.team.rag.retriever;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
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
public class Bm25Retriever {

    private final SearchIndexService searchIndexService;
    private final DocumentChunkService documentChunkService;
    private final DocumentRepository documentRepository;

    public List<RetrievedChunk> retrieve(TeamRetrievalQuery query) {
        if (query == null || query.knowledgeBaseIds() == null || query.knowledgeBaseIds().isEmpty()) {
            return List.of();
        }
        try {
            List<SearchChunkHit> hits = searchIndexService.searchChunkHits(
                    query.spaceId(),
                    query.knowledgeBaseIds(),
                    query.query(),
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

            List<RetrievedChunk> results = new ArrayList<>();
            for (DocumentChunk chunk : chunks) {
                Document document = documents.get(chunk.getDocumentId());
                SearchChunkHit hit = hitsByChunkId.get(chunk.getId());
                if (document == null
                        || hit == null
                        || document.getDeletedAt() != null
                        || document.getStatus() != DocumentStatus.INDEXED
                        || !document.getSpaceId().equals(query.spaceId())
                        || !query.knowledgeBaseIds().contains(document.getKnowledgeBaseId())
                        || document.getActiveIndexVersion() != chunk.getIndexVersion()
                        || !document.getId().equals(hit.documentId())
                        || !document.getKnowledgeBaseId().equals(hit.knowledgeBaseId())
                        || !document.getSpaceId().equals(hit.spaceId())
                        || chunk.getIndexVersion() != (hit.indexVersion() == null ? Integer.MIN_VALUE : hit.indexVersion())
                        || chunk.getChunkIndex() != (hit.chunkIndex() == null ? Integer.MIN_VALUE : hit.chunkIndex())) {
                    continue;
                }
                results.add(new RetrievedChunk(
                        chunk.getId(),
                        chunk.getDocumentId(),
                        chunk.getKnowledgeBaseId(),
                        chunk.getSpaceId(),
                        chunk.getIndexVersion(),
                        chunk.getChunkIndex(),
                        document.getTitle(),
                        chunk.getContent(),
                        hit.score() == null ? 1.0d : hit.score(),
                        chunk.getPageNo(),
                        chunk.getSourceStart(),
                        chunk.getSourceEnd(),
                        String.valueOf(chunk.getIndexVersion())
                ));
            }
            return results;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.RAG_RETRIEVAL_FAILED, "Failed to retrieve evidence");
        }
    }
}
