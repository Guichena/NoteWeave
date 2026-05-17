package com.noteweave.rageval.repository;

import com.noteweave.rageval.model.RagEvalResult;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RagEvalResultRepository extends JpaRepository<RagEvalResult, Long> {

    List<RagEvalResult> findByRunIdOrderByIdAsc(Long runId);
}
