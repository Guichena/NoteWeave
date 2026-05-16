package com.noteweave.personal.project.repository;

import com.noteweave.personal.project.model.ResearchProject;
import com.noteweave.personal.project.model.ResearchProjectStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ResearchProjectRepository extends JpaRepository<ResearchProject, Long> {

    List<ResearchProject> findBySpaceIdAndDeletedAtIsNullAndStatusOrderByCreatedAtDesc(Long spaceId, ResearchProjectStatus status);

    Optional<ResearchProject> findByIdAndSpaceIdAndDeletedAtIsNullAndStatus(Long id, Long spaceId, ResearchProjectStatus status);

    boolean existsByIdAndDeletedAtIsNull(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ResearchProject p where p.id = :id")
    Optional<ResearchProject> findByIdForUpdate(Long id);
}
