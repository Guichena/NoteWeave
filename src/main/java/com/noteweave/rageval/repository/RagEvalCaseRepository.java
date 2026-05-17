package com.noteweave.rageval.repository;

import com.noteweave.rageval.model.RagEvalCase;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RagEvalCaseRepository extends JpaRepository<RagEvalCase, Long> {

    List<RagEvalCase> findBySpaceIdOrderByUpdatedAtDescIdDesc(Long spaceId);

    List<RagEvalCase> findBySpaceIdAndEnabledTrueOrderByIdAsc(Long spaceId);
}
