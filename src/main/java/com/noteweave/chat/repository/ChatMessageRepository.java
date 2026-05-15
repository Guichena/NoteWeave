package com.noteweave.chat.repository;

import com.noteweave.chat.model.ChatMessage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findBySessionIdOrderByMessageSeqAsc(Long sessionId);

    Optional<ChatMessage> findTopBySessionIdOrderByMessageSeqDesc(Long sessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from ChatMessage m where m.id = :id")
    Optional<ChatMessage> findByIdForUpdate(Long id);
}
