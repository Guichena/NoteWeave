package com.noteweave.search.service;

import com.noteweave.permission.service.ResourceAccessService;
import com.noteweave.search.dto.SearchDebugResponse;
import com.noteweave.search.dto.SearchHitResponse;
import com.noteweave.team.document.model.Document;
import com.noteweave.team.document.model.DocumentChunk;
import com.noteweave.team.document.model.DocumentStatus;
import com.noteweave.team.document.repository.DocumentRepository;
import com.noteweave.team.document.service.DocumentChunkService;
import com.noteweave.team.kb.model.KnowledgeBase;
import com.noteweave.team.kb.service.KnowledgeBaseService;
import com.noteweave.team.rag.retriever.HybridRetriever;
import com.noteweave.team.rag.retriever.RetrievalHit;
import com.noteweave.team.rag.retriever.RetrievalMode;
import com.noteweave.team.rag.retriever.TeamRetrievalQuery;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SearchDebugService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final ResourceAccessService resourceAccessService;
    private final SearchIndexService searchIndexService;
    private final DocumentChunkService documentChunkService;
    private final DocumentRepository documentRepository;
    private final HybridRetriever hybridRetriever;

    @Transactional(readOnly = true)
    public SearchDebugResponse search(Long userId, Long knowledgeBaseId, String keyword, RetrievalMode mode) {
        KnowledgeBase kb = knowledgeBaseService.getRequiredActiveKb(knowledgeBaseId);
        resourceAccessService.requireViewSpace(userId, kb.getSpaceId());

        if (keyword == null || keyword.isBlank()) {
            return SearchDebugResponse.builder()
                    .items(List.of())
                    .retrievalMode(mode == null ? RetrievalMode.BM25 : mode)
                    .debug(SearchDebugResponse.SearchDebugMeta.builder()
                            .bm25Count(0)
                            .vectorCount(0)
                            .fusionCount(0)
                            .build())
                    .build();
        }
        HybridRetriever.HybridRetrievalResult retrieval = hybridRetriever.retrieve(
                new TeamRetrievalQuery(userId, kb.getSpaceId(), List.of(kb.getId()), keyword.trim(), 20, false),
                mode
        );
        List<SearchChunkHit> chunkHits = retrieval.fusedHits().stream()
                .map(hit -> new SearchChunkHit(
                        hit.chunkId(),
                        hit.documentId(),
                        hit.knowledgeBaseId(),
                        hit.spaceId(),
                        hit.metadata() == null ? null : (Integer) hit.metadata().get("indexVersion"),
                        hit.chunkIndex(),
                        hit.score()
                ))
                .toList();
        List<Long> chunkIds = chunkHits.stream().map(SearchChunkHit::chunkId).toList();
        List<DocumentChunk> chunks = documentChunkService.findByIdsInOrder(chunkIds);
        Map<Long, Document> documents = documentRepository.findAllById(
                        chunks.stream().map(DocumentChunk::getDocumentId).distinct().toList()
                )
                .stream()
                .collect(Collectors.toMap(Document::getId, Function.identity()));
        Map<Long, SearchChunkHit> hitsByChunkId = chunkHits.stream()
                .collect(Collectors.toMap(SearchChunkHit::chunkId, Function.identity(), (left, right) -> left));

        List<SearchHitResponse> items = chunks.stream()
                .filter(chunk -> {
                    Document document = documents.get(chunk.getDocumentId());
                    return document != null
                            && document.getDeletedAt() == null
                            && document.getStatus() == DocumentStatus.INDEXED
                            && document.getKnowledgeBaseId().equals(kb.getId())
                            && document.getSpaceId().equals(kb.getSpaceId())
                            && document.getActiveIndexVersion() == chunk.getIndexVersion();
                })
                .map(chunk -> {
                    Document document = documents.get(chunk.getDocumentId());
                    SearchChunkHit hit = hitsByChunkId.get(chunk.getId());
                    return SearchHitResponse.builder()
                            .chunkId(chunk.getId())
                            .documentId(chunk.getDocumentId())
                            .documentTitle(document.getTitle())
                            .chunkIndex(chunk.getChunkIndex())
                            .content(chunk.getContent())
                            .score(hit == null ? 1.0d : hit.score())
                            .build();
                })
                .toList();
        return SearchDebugResponse.builder()
                .items(items)
                .retrievalMode(retrieval.retrievalMode())
                .debug(SearchDebugResponse.SearchDebugMeta.builder()
                        .bm25Count(retrieval.bm25Count())
                        .vectorCount(retrieval.vectorCount())
                        .fusionCount(retrieval.fusionCount())
                        .build())
                .build();
    }
}
