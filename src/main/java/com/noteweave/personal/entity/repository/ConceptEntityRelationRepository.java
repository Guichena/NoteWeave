package com.noteweave.personal.entity.repository;

import com.noteweave.personal.entity.model.ConceptEntityRelation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConceptEntityRelationRepository extends JpaRepository<ConceptEntityRelation, Long> {

    List<ConceptEntityRelation> findByConceptCardIdOrderByIdAsc(Long conceptCardId);

    Optional<ConceptEntityRelation> findByConceptCardIdAndEntityCardIdAndRelationType(Long conceptCardId, Long entityCardId, String relationType);
}
