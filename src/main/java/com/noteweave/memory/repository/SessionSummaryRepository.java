package com.noteweave.memory.repository;

import com.noteweave.memory.model.SessionSummary;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SessionSummaryRepository extends JpaRepository<SessionSummary, Long> {

    List<SessionSummary> findByUserIdAndSessionIdOrderByCreatedAtDesc(Long userId, Long sessionId);

    @Query("""
            select s from SessionSummary s
            where s.userId = :userId
              and s.spaceId = :spaceId
              and (s.expiresAt is null or s.expiresAt > :now)
              and s.stale = false
            order by s.pin desc, s.importanceScore desc, s.updatedAt desc
            """)
    List<SessionSummary> findRelevant(Long userId, Long spaceId, LocalDateTime now);
}
