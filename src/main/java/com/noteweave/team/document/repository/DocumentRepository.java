package com.noteweave.team.document.repository;

import com.noteweave.team.document.model.Document;
import com.noteweave.team.document.model.DocumentStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findByKnowledgeBaseIdAndDeletedAtIsNullAndStatusNotOrderByCreatedAtDesc(Long knowledgeBaseId, DocumentStatus status);

    Optional<Document> findByIdAndDeletedAtIsNullAndStatusNot(Long id, DocumentStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Document d where d.id = :id")
    Optional<Document> findByIdForUpdate(Long id);
}
