package com.noteweave.artifact.repository;

import com.noteweave.artifact.model.ArtifactSource;
import com.noteweave.artifact.model.ArtifactSourceType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtifactSourceRepository extends JpaRepository<ArtifactSource, Long> {

    List<ArtifactSource> findByArtifactIdOrderByIdAsc(Long artifactId);

    List<ArtifactSource> findByArtifactIdInOrderByArtifactIdAscIdAsc(Collection<Long> artifactIds);

    Optional<ArtifactSource> findByArtifactIdAndSourceTypeAndSourceId(Long artifactId, ArtifactSourceType sourceType, Long sourceId);
}
