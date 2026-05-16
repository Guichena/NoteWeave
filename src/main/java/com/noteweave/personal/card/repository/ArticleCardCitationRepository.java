package com.noteweave.personal.card.repository;

import com.noteweave.personal.card.model.ArticleCardCitation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArticleCardCitationRepository extends JpaRepository<ArticleCardCitation, Long> {

    List<ArticleCardCitation> findByArticleCardIdOrderByIdAsc(Long articleCardId);

    Optional<ArticleCardCitation> findByArticleCardIdAndCitationIdAndRelationType(Long articleCardId, Long citationId, String relationType);

    void deleteByArticleCardId(Long articleCardId);
}
