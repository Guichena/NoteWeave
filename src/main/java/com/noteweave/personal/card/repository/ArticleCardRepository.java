package com.noteweave.personal.card.repository;

import com.noteweave.personal.card.model.ArticleCard;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArticleCardRepository extends JpaRepository<ArticleCard, Long> {

    Optional<ArticleCard> findBySourceId(Long sourceId);

    Optional<ArticleCard> findByIdAndSpaceId(Long id, Long spaceId);

    List<ArticleCard> findByResearchProjectIdAndSpaceIdOrderByUpdatedAtDesc(Long researchProjectId, Long spaceId);

    List<ArticleCard> findBySpaceIdOrderByUpdatedAtDesc(Long spaceId);
}
