package com.noteweave.artifact.repository;

import com.noteweave.artifact.model.ArtifactCardType;
import com.noteweave.artifact.model.ArtifactDistillationProposal;
import com.noteweave.artifact.model.ArtifactDistillationProposalStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

public interface ArtifactDistillationProposalRepository extends JpaRepository<ArtifactDistillationProposal, Long> {

    Optional<ArtifactDistillationProposal> findFirstByArtifactIdAndArtifactVersionIdAndUserIdAndCardTypeAndProposalStatusOrderByIdDesc(
            Long artifactId,
            Long artifactVersionId,
            Long userId,
            ArtifactCardType cardType,
            ArtifactDistillationProposalStatus proposalStatus
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ArtifactDistillationProposal> findByIdAndArtifactIdAndUserId(Long id, Long artifactId, Long userId);
}
