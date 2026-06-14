package com.noteweave.personal.entity.repository;

import com.noteweave.personal.entity.model.EntityCard;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface EntityCardRepository extends JpaRepository<EntityCard, Long> {

    List<EntityCard> findByResearchProjectIdAndSpaceIdOrderByUpdatedAtDesc(Long researchProjectId, Long spaceId);

    List<EntityCard> findBySpaceIdAndResearchProjectIdIsNullOrderByUpdatedAtDesc(Long spaceId);

    Optional<EntityCard> findByIdAndSpaceId(Long id, Long spaceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from EntityCard e where e.id = :id")
    Optional<EntityCard> findByIdForUpdate(Long id);
}
