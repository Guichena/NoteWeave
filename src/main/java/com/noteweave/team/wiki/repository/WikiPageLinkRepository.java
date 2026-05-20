package com.noteweave.team.wiki.repository;

import com.noteweave.team.wiki.model.WikiPageLink;
import com.noteweave.team.wiki.model.WikiPageLinkStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WikiPageLinkRepository extends JpaRepository<WikiPageLink, Long> {

    List<WikiPageLink> findBySpaceIdOrderBySourcePageIdAscIdAsc(Long spaceId);

    List<WikiPageLink> findBySourcePageIdOrderByIdAsc(Long sourcePageId);

    List<WikiPageLink> findByTargetPageIdOrderByIdAsc(Long targetPageId);

    List<WikiPageLink> findByTargetPageIdAndRelationStatusOrderByIdAsc(Long targetPageId, WikiPageLinkStatus relationStatus);

    void deleteBySpaceId(Long spaceId);
}
