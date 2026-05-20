package com.noteweave.team.wiki.repository;

import com.noteweave.team.wiki.model.WikiPageLink;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WikiPageLinkRepository extends JpaRepository<WikiPageLink, Long> {

    List<WikiPageLink> findBySpaceIdOrderBySourcePageIdAscIdAsc(Long spaceId);

    void deleteBySpaceId(Long spaceId);
}
