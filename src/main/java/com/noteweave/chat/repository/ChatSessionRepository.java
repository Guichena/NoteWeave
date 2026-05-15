package com.noteweave.chat.repository;

import com.noteweave.chat.model.ChatSession;
import com.noteweave.chat.model.ChatSessionStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    Optional<ChatSession> findByIdAndStatus(Long id, ChatSessionStatus status);

    List<ChatSession> findBySpaceIdAndStatusOrderByUpdatedAtDesc(Long spaceId, ChatSessionStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ChatSession s where s.id = :id")
    Optional<ChatSession> findByIdForUpdate(Long id);
}
