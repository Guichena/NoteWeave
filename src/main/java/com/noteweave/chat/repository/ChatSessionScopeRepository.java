package com.noteweave.chat.repository;

import com.noteweave.chat.model.ChatSessionScope;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatSessionScopeRepository extends JpaRepository<ChatSessionScope, Long> {

    List<ChatSessionScope> findBySessionIdOrderByIdAsc(Long sessionId);
}
