package com.noteweave.team.document.service;

import com.noteweave.team.document.chunk.ChunkCandidate;
import com.noteweave.team.document.dto.DocumentChunkResponse;
import com.noteweave.team.document.model.Document;
import com.noteweave.team.document.model.DocumentChunk;
import com.noteweave.team.document.repository.DocumentChunkRepository;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentChunkService {

    private final DocumentChunkRepository documentChunkRepository;

    @Transactional
    public List<DocumentChunk> createChunks(Document document, int indexVersion, List<ChunkCandidate> candidates) {
        if (documentChunkRepository.existsByDocumentIdAndIndexVersion(document.getId(), indexVersion)) {
            return documentChunkRepository.findByDocumentIdAndIndexVersionOrderByChunkIndexAsc(document.getId(), indexVersion);
        }
        List<DocumentChunk> chunks = candidates.stream()
                .map(candidate -> toEntity(document, indexVersion, candidate))
                .toList();
        return documentChunkRepository.saveAll(chunks);
    }

    public List<DocumentChunkResponse> listActiveChunks(Document document) {
        return documentChunkRepository.findByDocumentIdAndIndexVersionOrderByChunkIndexAsc(
                        document.getId(),
                        document.getActiveIndexVersion()
                )
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public List<DocumentChunk> findByIdsInOrder(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return documentChunkRepository.findByIdIn(ids).stream()
                .sorted(Comparator.comparingInt(chunk -> ids.stream().toList().indexOf(chunk.getId())))
                .toList();
    }

    public boolean activeChunksExist(Long documentId, int activeIndexVersion) {
        return activeIndexVersion > 0
                && documentChunkRepository.countByDocumentIdAndIndexVersion(documentId, activeIndexVersion) > 0;
    }

    private DocumentChunk toEntity(Document document, int indexVersion, ChunkCandidate candidate) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setSpaceId(document.getSpaceId());
        chunk.setKnowledgeBaseId(document.getKnowledgeBaseId());
        chunk.setDocumentId(document.getId());
        chunk.setIndexVersion(indexVersion);
        chunk.setChunkIndex(candidate.chunkIndex());
        chunk.setContent(candidate.content());
        chunk.setContentHash(candidate.contentHash());
        chunk.setTokenCount(candidate.tokenCount());
        chunk.setPageNo(candidate.pageNo());
        chunk.setSectionTitle(candidate.sectionTitle());
        chunk.setSourceStart(candidate.sourceStart());
        chunk.setSourceEnd(candidate.sourceEnd());
        chunk.setEsDocId("chunk:" + document.getId() + ":" + indexVersion + ":" + candidate.chunkIndex());
        return chunk;
    }

    private DocumentChunkResponse toResponse(DocumentChunk chunk) {
        return DocumentChunkResponse.builder()
                .id(chunk.getId())
                .spaceId(chunk.getSpaceId())
                .knowledgeBaseId(chunk.getKnowledgeBaseId())
                .documentId(chunk.getDocumentId())
                .indexVersion(chunk.getIndexVersion())
                .chunkIndex(chunk.getChunkIndex())
                .content(chunk.getContent())
                .contentHash(chunk.getContentHash())
                .tokenCount(chunk.getTokenCount())
                .pageNo(chunk.getPageNo())
                .sectionTitle(chunk.getSectionTitle())
                .sourceStart(chunk.getSourceStart())
                .sourceEnd(chunk.getSourceEnd())
                .esDocId(chunk.getEsDocId())
                .createdAt(chunk.getCreatedAt())
                .build();
    }
}
