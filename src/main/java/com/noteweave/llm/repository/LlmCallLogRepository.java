package com.noteweave.llm.repository;

import com.noteweave.llm.model.LlmCallLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmCallLogRepository extends JpaRepository<LlmCallLog, Long> {
}
