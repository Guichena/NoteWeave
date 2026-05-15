package com.noteweave.team.document.repository;

import com.noteweave.team.document.model.FileObject;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface FileObjectRepository extends JpaRepository<FileObject, Long> {

    Optional<FileObject> findBySpaceIdAndContentHash(Long spaceId, String contentHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FileObject f where f.spaceId = :spaceId and f.contentHash = :contentHash")
    Optional<FileObject> findBySpaceIdAndContentHashForUpdate(Long spaceId, String contentHash);
}
