package com.noteweave.artifact.repository;

import com.noteweave.artifact.model.ArtifactCitation;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtifactCitationRepository extends JpaRepository<ArtifactCitation, Long> {

    List<ArtifactCitation> findByArtifactIdOrderByIdAsc(Long artifactId);

    List<ArtifactCitation> findByArtifactIdInOrderByArtifactIdAscIdAsc(Collection<Long> artifactIds);

    Optional<ArtifactCitation> findByArtifactIdAndCitationId(Long artifactId, Long citationId);
}
