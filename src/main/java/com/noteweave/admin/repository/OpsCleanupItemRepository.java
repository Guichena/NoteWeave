package com.noteweave.admin.repository;

import com.noteweave.admin.model.OpsCleanupItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OpsCleanupItemRepository extends JpaRepository<OpsCleanupItem, Long> {

    List<OpsCleanupItem> findByJobIdOrderByIdAsc(Long jobId);
}
