package com.noteweave.personal.card.repository;

import com.noteweave.personal.card.model.SynthesisCardCitation;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SynthesisCardCitationRepository extends JpaRepository<SynthesisCardCitation, Long> {

    List<SynthesisCardCitation> findBySynthesisCardIdOrderByIdAsc(Long synthesisCardId);

    List<SynthesisCardCitation> findBySynthesisCardIdInOrderBySynthesisCardIdAscIdAsc(Collection<Long> synthesisCardIds);

    Optional<SynthesisCardCitation> findBySynthesisCardIdAndCitationIdAndRelationType(Long synthesisCardId, Long citationId, String relationType);
}
