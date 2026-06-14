package com.noteweave.personal.entity.repository;

import com.noteweave.personal.entity.model.ClaimEntityRelation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimEntityRelationRepository extends JpaRepository<ClaimEntityRelation, Long> {

    List<ClaimEntityRelation> findByClaimIdOrderByIdAsc(Long claimId);

    Optional<ClaimEntityRelation> findByClaimIdAndEntityCardIdAndRelationType(Long claimId, Long entityCardId, String relationType);
}
