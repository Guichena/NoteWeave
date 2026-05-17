package com.noteweave.personal.methodology.repository;

import com.noteweave.personal.methodology.model.MethodologyCard;
import com.noteweave.personal.methodology.model.MethodologyCardSource;
import com.noteweave.personal.methodology.model.MethodologyCardStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface MethodologyCardRepository extends JpaRepository<MethodologyCard, Long> {

    List<MethodologyCard> findByResearchProjectIdAndSpaceIdAndStatusOrderByUpdatedAtDesc(
            Long researchProjectId,
            Long spaceId,
            MethodologyCardStatus status
    );

    List<MethodologyCard> findBySpaceIdAndResearchProjectIdIsNullAndStatusOrderByUpdatedAtDesc(
            Long spaceId,
            MethodologyCardStatus status
    );

    List<MethodologyCard> findByCardSourceAndStatusOrderByUpdatedAtDesc(
            MethodologyCardSource cardSource,
            MethodologyCardStatus status
    );

    Optional<MethodologyCard> findBySpaceIdAndResearchProjectIdIsNullAndNameAndCardSource(
            Long spaceId,
            String name,
            MethodologyCardSource cardSource
    );

    List<MethodologyCard> findBySpaceIdAndResearchProjectIdAndCardSource(Long spaceId, Long researchProjectId, MethodologyCardSource cardSource);

    List<MethodologyCard> findBySpaceIdAndResearchProjectIdIsNullAndCardSource(Long spaceId, MethodologyCardSource cardSource);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from MethodologyCard c where c.id = :id")
    Optional<MethodologyCard> findByIdForUpdate(Long id);
}
