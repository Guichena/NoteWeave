package com.noteweave.personal.question.repository;

import com.noteweave.personal.question.model.ResearchQuestionOverview;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResearchQuestionOverviewRepository extends JpaRepository<ResearchQuestionOverview, Long> {

    Optional<ResearchQuestionOverview> findTopByResearchQuestionIdOrderByUpdatedAtDesc(Long researchQuestionId);

    List<ResearchQuestionOverview> findByResearchQuestionIdOrderByUpdatedAtDesc(Long researchQuestionId);
}
