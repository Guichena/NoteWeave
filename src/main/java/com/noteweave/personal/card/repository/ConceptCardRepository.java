package com.noteweave.personal.card.repository;

import com.noteweave.personal.card.model.ConceptCard;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConceptCardRepository extends JpaRepository<ConceptCard, Long> {

    Optional<ConceptCard> findByIdAndSpaceId(Long id, Long spaceId);

    Optional<ConceptCard> findByResearchProjectIdAndNormalizedName(Long researchProjectId, String normalizedName);

    List<ConceptCard> findByResearchProjectIdAndSpaceIdOrderByUpdatedAtDesc(Long researchProjectId, Long spaceId);

    List<ConceptCard> findBySpaceIdOrderByUpdatedAtDesc(Long spaceId);

    List<ConceptCard> findByIdIn(Collection<Long> ids);
}
