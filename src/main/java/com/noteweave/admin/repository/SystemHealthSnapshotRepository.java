package com.noteweave.admin.repository;

import com.noteweave.admin.model.SystemHealthComponent;
import com.noteweave.admin.model.SystemHealthSnapshot;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemHealthSnapshotRepository extends JpaRepository<SystemHealthSnapshot, Long> {

    List<SystemHealthSnapshot> findTop20ByComponentOrderByCheckedAtDesc(SystemHealthComponent component);
}
