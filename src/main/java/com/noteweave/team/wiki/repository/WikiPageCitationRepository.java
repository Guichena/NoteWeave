package com.noteweave.team.wiki.repository;

import com.noteweave.team.wiki.model.WikiPageCitation;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WikiPageCitationRepository extends JpaRepository<WikiPageCitation, Long> {

    List<WikiPageCitation> findByWikiPageIdOrderByIdAsc(Long wikiPageId);

    List<WikiPageCitation> findByWikiPageIdInOrderByWikiPageIdAscIdAsc(Collection<Long> wikiPageIds);

    List<WikiPageCitation> findByWikiPageIdAndWikiPageVersionIdOrderByIdAsc(Long wikiPageId, Long wikiPageVersionId);

    Optional<WikiPageCitation> findByWikiPageIdAndWikiPageVersionIdAndCitationIdAndRelationType(
            Long wikiPageId,
            Long wikiPageVersionId,
            Long citationId,
            String relationType
    );
}
