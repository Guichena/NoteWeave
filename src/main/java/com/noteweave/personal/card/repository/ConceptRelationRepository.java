package com.noteweave.personal.card.repository;

import com.noteweave.personal.card.model.ConceptRelation;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConceptRelationRepository extends JpaRepository<ConceptRelation, Long> {

    List<ConceptRelation> findByResearchProjectIdAndSourceConceptIdOrResearchProjectIdAndTargetConceptId(
            Long researchProjectIdForSource,
            Long sourceConceptId,
            Long researchProjectIdForTarget,
            Long targetConceptId
    );

    List<ConceptRelation> findBySourceConceptIdInOrTargetConceptIdIn(Collection<Long> sourceConceptIds, Collection<Long> targetConceptIds);

    List<ConceptRelation> findBySourceConceptIdInOrderBySourceConceptIdAscIdAsc(Collection<Long> sourceConceptIds);

    Optional<ConceptRelation> findBySourceConceptIdAndTargetConceptIdAndRelationType(Long sourceConceptId, Long targetConceptId, String relationType);

    List<ConceptRelation> findByResearchProjectId(Long researchProjectId);
}
