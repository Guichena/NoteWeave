package com.noteweave.personal.card.repository;

import com.noteweave.personal.card.model.SynthesisConceptRelation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SynthesisConceptRelationRepository extends JpaRepository<SynthesisConceptRelation, Long> {

    List<SynthesisConceptRelation> findBySynthesisCardIdOrderByIdAsc(Long synthesisCardId);

    Optional<SynthesisConceptRelation> findBySynthesisCardIdAndConceptCardIdAndRelationType(
            Long synthesisCardId,
            Long conceptCardId,
            String relationType
    );
}
