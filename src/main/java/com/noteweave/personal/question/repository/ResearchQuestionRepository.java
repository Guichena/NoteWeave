package com.noteweave.personal.question.repository;

import com.noteweave.personal.question.model.ResearchQuestion;
import com.noteweave.personal.question.model.ResearchQuestionStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ResearchQuestionRepository extends JpaRepository<ResearchQuestion, Long> {

    List<ResearchQuestion> findByResearchProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long researchProjectId);

    List<ResearchQuestion> findBySpaceIdAndDeletedAtIsNullAndStatusOrderByUpdatedAtDesc(Long spaceId, ResearchQuestionStatus status);

    Optional<ResearchQuestion> findByIdAndSpaceIdAndDeletedAtIsNull(Long id, Long spaceId);

    List<ResearchQuestion> findByIdInAndDeletedAtIsNull(Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from ResearchQuestion q where q.id = :id")
    Optional<ResearchQuestion> findByIdForUpdate(Long id);
}
