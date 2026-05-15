package com.noteweave.team.document.repository;

import com.noteweave.team.document.model.DocumentUpload;
import com.noteweave.team.document.model.DocumentUploadStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface DocumentUploadRepository extends JpaRepository<DocumentUpload, Long> {

    Optional<DocumentUpload> findByIdAndStatusNot(Long id, DocumentUploadStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from DocumentUpload u where u.id = :id")
    Optional<DocumentUpload> findByIdForUpdate(Long id);

    Optional<DocumentUpload> findFirstBySpaceIdAndFileMd5AndStatusInOrderByMergedAtDescIdDesc(
            Long spaceId,
            String fileMd5,
            Collection<DocumentUploadStatus> statuses
    );

    List<DocumentUpload> findByExpiresAtBeforeAndStatusIn(LocalDateTime now, Collection<DocumentUploadStatus> statuses);
}
