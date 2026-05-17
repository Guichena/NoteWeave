package com.noteweave.memory.repository;

import com.noteweave.memory.model.SpaceMemory;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpaceMemoryRepository extends JpaRepository<SpaceMemory, Long> {

    Optional<SpaceMemory> findByUserIdAndSpaceId(Long userId, Long spaceId);
}
