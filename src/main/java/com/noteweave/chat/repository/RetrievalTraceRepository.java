package com.noteweave.chat.repository;

import com.noteweave.chat.model.RetrievalTrace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface RetrievalTraceRepository extends JpaRepository<RetrievalTrace, Long>, JpaSpecificationExecutor<RetrievalTrace> {
}
