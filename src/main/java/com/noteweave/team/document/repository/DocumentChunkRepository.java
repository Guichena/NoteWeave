package com.noteweave.team.document.repository;

import com.noteweave.team.document.model.DocumentChunk;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    List<DocumentChunk> findByDocumentIdAndIndexVersionOrderByChunkIndexAsc(Long documentId, int indexVersion);

    List<DocumentChunk> findByDocumentIdOrderByIndexVersionDescChunkIndexAsc(Long documentId);

    List<DocumentChunk> findByIdIn(Collection<Long> ids);

    long countByDocumentIdAndIndexVersion(Long documentId, int indexVersion);

    boolean existsByDocumentIdAndIndexVersion(Long documentId, int indexVersion);

    @Modifying
    void deleteByDocumentIdAndIndexVersion(Long documentId, int indexVersion);
}
