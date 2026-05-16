package com.noteweave.personal.card.repository;

import com.noteweave.personal.card.model.ConceptCardCitation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConceptCardCitationRepository extends JpaRepository<ConceptCardCitation, Long> {

    List<ConceptCardCitation> findByConceptCardIdOrderByIdAsc(Long conceptCardId);

    Optional<ConceptCardCitation> findByConceptCardIdAndCitationIdAndRelationType(Long conceptCardId, Long citationId, String relationType);
}
