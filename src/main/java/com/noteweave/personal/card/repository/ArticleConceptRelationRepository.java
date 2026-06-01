package com.noteweave.personal.card.repository;

import com.noteweave.personal.card.model.ArticleConceptRelation;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArticleConceptRelationRepository extends JpaRepository<ArticleConceptRelation, Long> {

    List<ArticleConceptRelation> findByArticleCardIdOrderByIdAsc(Long articleCardId);

    List<ArticleConceptRelation> findByArticleCardIdInOrderByArticleCardIdAscIdAsc(Collection<Long> articleCardIds);

    List<ArticleConceptRelation> findByConceptCardIdOrderByIdAsc(Long conceptCardId);

    Optional<ArticleConceptRelation> findByArticleCardIdAndConceptCardId(Long articleCardId, Long conceptCardId);

    void deleteByArticleCardId(Long articleCardId);
}
