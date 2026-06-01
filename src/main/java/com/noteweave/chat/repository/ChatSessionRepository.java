package com.noteweave.chat.repository;

import com.noteweave.chat.model.ChatDraftStatus;
import com.noteweave.chat.model.ChatSession;
import com.noteweave.chat.model.ChatSessionKind;
import com.noteweave.chat.model.ChatSessionStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    Optional<ChatSession> findByIdAndStatus(Long id, ChatSessionStatus status);

    List<ChatSession> findBySpaceIdAndStatusOrderByUpdatedAtDesc(Long spaceId, ChatSessionStatus status);

    @Query("""
            select s from ChatSession s
            where s.sessionKind = :sessionKind
              and s.draftStatus = :draftStatus
              and coalesce(s.lastActiveAt, s.createdAt) < :threshold
            """)
    List<ChatSession> findExpiredDrafts(ChatSessionKind sessionKind, ChatDraftStatus draftStatus, LocalDateTime threshold);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ChatSession s where s.id = :id")
    Optional<ChatSession> findByIdForUpdate(Long id);
}
