package com.noteweave.admin.repository;

import com.noteweave.admin.model.OpsCleanupJob;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface OpsCleanupJobRepository extends JpaRepository<OpsCleanupJob, Long>, JpaSpecificationExecutor<OpsCleanupJob> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from OpsCleanupJob j where j.id = :id")
    Optional<OpsCleanupJob> findByIdForUpdate(Long id);
}
