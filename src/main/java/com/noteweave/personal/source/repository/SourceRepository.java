package com.noteweave.personal.source.repository;

import com.noteweave.personal.source.model.Source;
import com.noteweave.personal.source.model.SourceType;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface SourceRepository extends JpaRepository<Source, Long> {

    List<Source> findByResearchProjectIdAndSpaceIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long researchProjectId, Long spaceId);

    List<Source> findByResearchProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long researchProjectId);

    List<Source> findBySpaceIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long spaceId);

    Optional<Source> findByIdAndSpaceIdAndDeletedAtIsNull(Long id, Long spaceId);

    boolean existsByIdAndDeletedAtIsNull(Long id);

    Optional<Source> findFirstByResearchProjectIdAndDeletedAtIsNullAndContentHashOrderByCreatedAtAsc(
            Long researchProjectId,
            String contentHash
    );

    Optional<Source> findFirstByResearchProjectIdAndDeletedAtIsNullAndSourceTypeAndUrlOrderByCreatedAtAsc(
            Long researchProjectId,
            SourceType sourceType,
            String url
    );

    Optional<Source> findFirstByResearchProjectIdAndDeletedAtIsNullAndContentHashAndIdNotOrderByCreatedAtAsc(
            Long researchProjectId,
            String contentHash,
            Long id
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Source s where s.id = :id")
    Optional<Source> findByIdForUpdate(Long id);
}
