package com.noteweave.memory.repository;

import com.noteweave.memory.model.UserMemory;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserMemoryRepository extends JpaRepository<UserMemory, Long> {

    Optional<UserMemory> findByUserId(Long userId);
}
