package com.noteweave.team.wiki.repository;

import com.noteweave.team.wiki.model.WikiPageVersion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface WikiPageVersionRepository extends JpaRepository<WikiPageVersion, Long> {

    List<WikiPageVersion> findByWikiPageIdOrderByVersionNoAsc(Long wikiPageId);

    Optional<WikiPageVersion> findTopByWikiPageIdOrderByVersionNoDesc(Long wikiPageId);

    @Query("select coalesce(max(v.versionNo), 0) from WikiPageVersion v where v.wikiPageId = :wikiPageId")
    int findMaxVersionNo(Long wikiPageId);
}
