package com.noteweave.artifact.repository;

import com.noteweave.artifact.model.ArtifactVersion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ArtifactVersionRepository extends JpaRepository<ArtifactVersion, Long> {

    List<ArtifactVersion> findByArtifactIdOrderByVersionNoAsc(Long artifactId);

    Optional<ArtifactVersion> findTopByArtifactIdOrderByVersionNoDesc(Long artifactId);

    @Query("select coalesce(max(v.versionNo), 0) from ArtifactVersion v where v.artifactId = :artifactId")
    int findMaxVersionNo(Long artifactId);
}
