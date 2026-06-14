package com.noteweave.personal.claim.repository;

import com.noteweave.personal.claim.model.ClaimCitation;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimCitationRepository extends JpaRepository<ClaimCitation, Long> {

    List<ClaimCitation> findByClaimIdOrderByIdAsc(Long claimId);

    List<ClaimCitation> findByClaimIdInOrderByClaimIdAscIdAsc(Collection<Long> claimIds);

    Optional<ClaimCitation> findByClaimIdAndCitationId(Long claimId, Long citationId);

    void deleteByClaimId(Long claimId);
}
