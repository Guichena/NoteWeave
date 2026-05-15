package com.noteweave.team.kb.repository;

import com.noteweave.team.kb.model.KnowledgeBase;
import com.noteweave.team.kb.model.KnowledgeBaseStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {

    List<KnowledgeBase> findBySpaceIdAndStatusOrderByCreatedAtDesc(Long spaceId, KnowledgeBaseStatus status);

    List<KnowledgeBase> findBySpaceIdAndStatus(Long spaceId, KnowledgeBaseStatus status);

    List<KnowledgeBase> findBySpaceIdAndStatusAndIdIn(Long spaceId, KnowledgeBaseStatus status, List<Long> ids);

    Optional<KnowledgeBase> findByIdAndStatus(Long id, KnowledgeBaseStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select kb from KnowledgeBase kb where kb.id = :id")
    Optional<KnowledgeBase> findByIdForUpdate(Long id);
}
