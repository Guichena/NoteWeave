package com.noteweave.artifact.repository;

import com.noteweave.artifact.model.ArtifactCardRelation;
import com.noteweave.artifact.model.ArtifactCardRelationType;
import com.noteweave.artifact.model.ArtifactCardType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtifactCardRelationRepository extends JpaRepository<ArtifactCardRelation, Long> {

    List<ArtifactCardRelation> findByArtifactIdOrderByIdAsc(Long artifactId);

    Optional<ArtifactCardRelation> findByArtifactIdAndArtifactVersionIdAndCardTypeAndRelationType(
            Long artifactId,
            Long artifactVersionId,
            ArtifactCardType cardType,
            ArtifactCardRelationType relationType
    );
}
