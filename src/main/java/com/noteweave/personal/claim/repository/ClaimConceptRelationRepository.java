package com.noteweave.personal.claim.repository;

import com.noteweave.personal.claim.model.ClaimConceptRelation;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimConceptRelationRepository extends JpaRepository<ClaimConceptRelation, Long> {

    List<ClaimConceptRelation> findByClaimId(Long claimId);

    List<ClaimConceptRelation> findByClaimIdInOrderByClaimIdAscIdAsc(Collection<Long> claimIds);

    List<ClaimConceptRelation> findByConceptCardId(Long conceptCardId);

    void deleteByClaimId(Long claimId);
}
