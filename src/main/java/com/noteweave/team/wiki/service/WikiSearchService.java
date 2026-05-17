package com.noteweave.team.wiki.service;

import com.noteweave.permission.service.ResourceAccessService;
import com.noteweave.team.wiki.dto.WikiSearchItemResponse;
import com.noteweave.team.wiki.dto.WikiSearchResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WikiSearchService {

    private final ResourceAccessService resourceAccessService;
    private final SearchIndexWikiSupport searchIndexWikiSupport;

    @Transactional(readOnly = true)
    public WikiSearchResponse search(Long userId, Long spaceId, String keyword) {
        resourceAccessService.requireViewSpace(userId, spaceId);
        if (keyword == null || keyword.isBlank()) {
            return WikiSearchResponse.builder().items(List.of()).build();
        }
        return WikiSearchResponse.builder()
                .items(searchIndexWikiSupport.search(spaceId, keyword.trim(), 20).stream()
                        .map(hit -> WikiSearchItemResponse.builder()
                                .id(hit.wikiPageId())
                                .title(hit.title())
                                .content(hit.content())
                                .score(hit.score())
                                .build())
                        .toList())
                .build();
    }
}
