package com.noteweave.artifact.repository;

import com.noteweave.artifact.model.SessionArtifact;
import com.noteweave.artifact.model.SessionArtifactRelationType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionArtifactRepository extends JpaRepository<SessionArtifact, Long> {

    List<SessionArtifact> findBySessionIdOrderByIdAsc(Long sessionId);

    Optional<SessionArtifact> findBySessionIdAndArtifactIdAndRelationType(
            Long sessionId,
            Long artifactId,
            SessionArtifactRelationType relationType
    );
}
