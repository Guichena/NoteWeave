package com.noteweave.team.wiki.service;

import com.noteweave.team.wiki.model.WikiPage;
import com.noteweave.team.wiki.model.WikiPageLink;
import com.noteweave.team.wiki.model.WikiPageLinkStatus;
import com.noteweave.team.wiki.repository.WikiPageLinkRepository;
import com.noteweave.team.wiki.repository.WikiPageRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WikiGraphSyncService {

    private final WikiPageRepository wikiPageRepository;
    private final WikiPageLinkRepository wikiPageLinkRepository;
    private final WikiLinkParser wikiLinkParser;

    @Transactional
    public void rebuildSpaceGraph(Long spaceId) {
        List<WikiPage> pages = wikiPageRepository.findBySpaceIdAndDeletedAtIsNullOrderByUpdatedAtDesc(spaceId);
        wikiPageLinkRepository.deleteBySpaceId(spaceId);
        if (pages.isEmpty()) {
            return;
        }

        Map<String, List<WikiPage>> pagesByTitle = pages.stream()
                .filter(page -> wikiLinkParser.normalizeLookupKey(page.getTitle()) != null)
                .collect(Collectors.groupingBy(
                        page -> wikiLinkParser.normalizeLookupKey(page.getTitle()),
                        Collectors.toList()
                ));

        List<WikiPageLink> links = new ArrayList<>();
        for (WikiPage page : pages) {
            Map<String, Integer> targetMentions = wikiLinkParser.extractLinkTargets(page.getContent());
            for (Map.Entry<String, Integer> entry : targetMentions.entrySet()) {
                WikiPageLink link = new WikiPageLink();
                link.setSpaceId(spaceId);
                link.setSourcePageId(page.getId());
                link.setTargetTitle(entry.getKey());
                link.setMentionCount(entry.getValue());

                List<WikiPage> candidates = pagesByTitle.getOrDefault(
                        wikiLinkParser.normalizeLookupKey(entry.getKey()),
                        List.of()
                );
                if (candidates.isEmpty()) {
                    link.setRelationStatus(WikiPageLinkStatus.MISSING);
                } else if (candidates.size() > 1) {
                    link.setRelationStatus(WikiPageLinkStatus.AMBIGUOUS);
                } else {
                    link.setRelationStatus(WikiPageLinkStatus.RESOLVED);
                    link.setTargetPageId(candidates.get(0).getId());
                }
                links.add(link);
            }
        }

        if (!links.isEmpty()) {
            wikiPageLinkRepository.saveAll(links);
        }
    }
}
