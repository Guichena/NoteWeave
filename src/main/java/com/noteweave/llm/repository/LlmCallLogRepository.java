package com.noteweave.llm.repository;

import com.noteweave.llm.model.LlmCallLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface LlmCallLogRepository extends JpaRepository<LlmCallLog, Long>, JpaSpecificationExecutor<LlmCallLog> {
}
