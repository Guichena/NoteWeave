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

    @Transactional(readOnly = true)
    public SearchDebugResponse search(Long userId, Long knowledgeBaseId, String keyword) {
        KnowledgeBase kb = knowledgeBaseService.getRequiredActiveKb(knowledgeBaseId);
        resourceAccessService.requireViewSpace(userId, kb.getSpaceId());

        if (keyword == null || keyword.isBlank()) {
            return SearchDebugResponse.builder().items(List.of()).build();
        }
        List<Long> chunkIds = searchIndexService.searchChunkIds(kb.getSpaceId(), kb.getId(), keyword.trim(), 20);
        List<DocumentChunk> chunks = documentChunkService.findByIdsInOrder(chunkIds);
        Map<Long, Document> documents = documentRepository.findAllById(
                        chunks.stream().map(DocumentChunk::getDocumentId).distinct().toList()
                )
                .stream()
                .collect(Collectors.toMap(Document::getId, Function.identity()));

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
                    return SearchHitResponse.builder()
                            .chunkId(chunk.getId())
                            .documentId(chunk.getDocumentId())
                            .documentTitle(document.getTitle())
                            .chunkIndex(chunk.getChunkIndex())
                            .content(chunk.getContent())
                            .score(1.0)
                            .build();
                })
                .toList();
        return SearchDebugResponse.builder().items(items).build();
    }
}
