package com.noteweave.personal.methodology.repository;

import com.noteweave.personal.methodology.model.MethodologyCard;
import com.noteweave.personal.methodology.model.MethodologyCardSource;
import com.noteweave.personal.methodology.model.MethodologyCardStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MethodologyCardRepository extends JpaRepository<MethodologyCard, Long> {

    List<MethodologyCard> findByResearchProjectIdAndStatusOrderByUpdatedAtDesc(
            Long researchProjectId,
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
}
