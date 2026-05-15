package com.noteweave.chat.repository;

import com.noteweave.chat.model.RetrievalTrace;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RetrievalTraceRepository extends JpaRepository<RetrievalTrace, Long> {
}
