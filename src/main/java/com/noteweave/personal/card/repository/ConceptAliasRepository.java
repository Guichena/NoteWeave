package com.noteweave.personal.card.repository;

import com.noteweave.personal.card.model.ConceptAlias;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ConceptAliasRepository extends JpaRepository<ConceptAlias, Long> {

    List<ConceptAlias> findByConceptCardIdOrderByAliasAsc(Long conceptCardId);

    @Query("""
            select a from ConceptAlias a
            join ConceptCard c on c.id = a.conceptCardId
            where c.researchProjectId = :researchProjectId and a.normalizedAlias = :normalizedAlias
            """)
    Optional<ConceptAlias> findByResearchProjectIdAndNormalizedAlias(Long researchProjectId, String normalizedAlias);
}
