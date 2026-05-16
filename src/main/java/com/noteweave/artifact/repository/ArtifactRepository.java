package com.noteweave.artifact.repository;

import com.noteweave.artifact.model.Artifact;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface ArtifactRepository extends JpaRepository<Artifact, Long>, JpaSpecificationExecutor<Artifact> {

    List<Artifact> findBySpaceIdAndDeletedAtIsNullOrderByUpdatedAtDesc(Long spaceId);

    Optional<Artifact> findByIdAndDeletedAtIsNull(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Artifact a where a.id = :id")
    Optional<Artifact> findByIdForUpdate(Long id);
}
