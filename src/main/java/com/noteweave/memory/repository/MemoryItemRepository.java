package com.noteweave.memory.repository;

import com.noteweave.memory.model.MemoryItem;
import com.noteweave.memory.model.MemoryType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MemoryItemRepository extends JpaRepository<MemoryItem, Long> {

    @Query("""
            select m from MemoryItem m
            where m.userId = :userId
              and ((:spaceId is null and m.spaceId is null) or m.spaceId = :spaceId)
              and m.deletedAt is null
              and m.memoryType = :memoryType
              and m.topic = :topic
            order by m.confidenceScore desc, m.updatedAt desc
            """)
    List<MemoryItem> findCandidates(Long userId, Long spaceId, MemoryType memoryType, String topic);

    @Query("""
            select m from MemoryItem m
            where m.userId = :userId
              and m.spaceId = :spaceId
              and m.deletedAt is null
              and (m.pin = true or m.expiresAt is null or m.expiresAt > :now)
            order by m.pin desc, m.importanceScore desc, m.updatedAt desc
            """)
    List<MemoryItem> findActiveByUserIdAndSpaceId(Long userId, Long spaceId, LocalDateTime now);

    @Query("""
            select m from MemoryItem m
            where m.userId = :userId
              and m.spaceId = :spaceId
              and m.deletedAt is null
              and (m.expiresAt is null or m.expiresAt > :now)
            order by m.pin desc, m.importanceScore desc, m.updatedAt desc
            """)
    List<MemoryItem> findContextEligibleByUserIdAndSpaceId(Long userId, Long spaceId, LocalDateTime now);

    @Query("""
            select m from MemoryItem m
            where m.userId = :userId
              and m.spaceId is null
              and m.deletedAt is null
              and (m.pin = true or m.expiresAt is null or m.expiresAt > :now)
            order by m.pin desc, m.importanceScore desc, m.updatedAt desc
            """)
    List<MemoryItem> findActiveUserLevel(Long userId, LocalDateTime now);

    @Query("""
            select m from MemoryItem m
            where m.userId = :userId
              and m.spaceId is null
              and m.deletedAt is null
              and (m.expiresAt is null or m.expiresAt > :now)
            order by m.pin desc, m.importanceScore desc, m.updatedAt desc
            """)
    List<MemoryItem> findContextEligibleUserLevel(Long userId, LocalDateTime now);

    Optional<MemoryItem> findByIdAndUserIdAndSpaceId(Long id, Long userId, Long spaceId);

    Optional<MemoryItem> findByIdAndUserIdAndSpaceIdIsNull(Long id, Long userId);

    @Query("""
            select m from MemoryItem m
            where m.deletedAt is null
              and m.pin = false
              and m.expiresAt is not null
              and m.expiresAt <= :now
            """)
    List<MemoryItem> findExpiredUnpinned(LocalDateTime now);
}
