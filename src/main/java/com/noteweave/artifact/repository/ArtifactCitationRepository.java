package com.noteweave.artifact.repository;

import com.noteweave.artifact.model.ArtifactCitation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtifactCitationRepository extends JpaRepository<ArtifactCitation, Long> {

    List<ArtifactCitation> findByArtifactIdOrderByIdAsc(Long artifactId);

    Optional<ArtifactCitation> findByArtifactIdAndCitationId(Long artifactId, Long citationId);
}
