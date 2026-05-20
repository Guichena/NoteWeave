package com.noteweave.team.wiki.service;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.permission.service.ResourceAccessService;
import com.noteweave.team.wiki.dto.WikiGraphEdgeResponse;
import com.noteweave.team.wiki.dto.WikiGraphNodeResponse;
import com.noteweave.team.wiki.dto.WikiGraphResponse;
import com.noteweave.team.wiki.dto.WikiGraphUnresolvedLinkResponse;
import com.noteweave.team.wiki.model.WikiPage;
import com.noteweave.team.wiki.model.WikiPageLink;
import com.noteweave.team.wiki.model.WikiPageLinkStatus;
import com.noteweave.team.wiki.repository.WikiPageLinkRepository;
import com.noteweave.team.wiki.repository.WikiPageRepository;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WikiGraphService {

    private static final int MAX_DEPTH = 4;

    private final WikiPageRepository wikiPageRepository;
    private final WikiPageLinkRepository wikiPageLinkRepository;
    private final TeamWikiService teamWikiService;
    private final ResourceAccessService resourceAccessService;

    @Transactional(readOnly = true)
    public WikiGraphResponse getSpaceGraph(Long userId, Long spaceId) {
        resourceAccessService.requireViewSpace(userId, spaceId);
        List<WikiPage> pages = wikiPageRepository.findBySpaceIdAndDeletedAtIsNullOrderByUpdatedAtDesc(spaceId);
        List<WikiPageLink> links = wikiPageLinkRepository.findBySpaceIdOrderBySourcePageIdAscIdAsc(spaceId);
        return buildGraph(spaceId, null, null, pages, links, null);
    }

    @Transactional(readOnly = true)
    public WikiGraphResponse getPageSubgraph(Long userId, Long pageId, int depth) {
        if (depth < 0 || depth > MAX_DEPTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "depth must be between 0 and " + MAX_DEPTH);
        }
        WikiPage rootPage = teamWikiService.getRequiredReadablePage(userId, pageId);
        List<WikiPage> pages = wikiPageRepository.findBySpaceIdAndDeletedAtIsNullOrderByUpdatedAtDesc(rootPage.getSpaceId());
        List<WikiPageLink> links = wikiPageLinkRepository.findBySpaceIdOrderBySourcePageIdAscIdAsc(rootPage.getSpaceId());
        Set<Long> includedNodeIds = collectNodeIds(rootPage.getId(), depth, links);
        return buildGraph(rootPage.getSpaceId(), rootPage.getId(), depth, pages, links, includedNodeIds);
    }

    private Set<Long> collectNodeIds(Long rootPageId, int depth, List<WikiPageLink> links) {
        Set<Long> visited = new LinkedHashSet<>();
        visited.add(rootPageId);
        if (depth == 0) {
            return visited;
        }

        Map<Long, Set<Long>> adjacency = new HashMap<>();
        for (WikiPageLink link : links) {
            if (link.getRelationStatus() != WikiPageLinkStatus.RESOLVED
                    || link.getTargetPageId() == null
                    || Objects.equals(link.getSourcePageId(), link.getTargetPageId())) {
                continue;
            }
            adjacency.computeIfAbsent(link.getSourcePageId(), ignored -> new LinkedHashSet<>()).add(link.getTargetPageId());
            adjacency.computeIfAbsent(link.getTargetPageId(), ignored -> new LinkedHashSet<>()).add(link.getSourcePageId());
        }

        ArrayDeque<PageDepth> queue = new ArrayDeque<>();
        queue.add(new PageDepth(rootPageId, 0));
        while (!queue.isEmpty()) {
            PageDepth current = queue.removeFirst();
            if (current.depth() >= depth) {
                continue;
            }
            for (Long neighborId : adjacency.getOrDefault(current.pageId(), Set.of())) {
                if (visited.add(neighborId)) {
                    queue.addLast(new PageDepth(neighborId, current.depth() + 1));
                }
            }
        }
        return visited;
    }

    private WikiGraphResponse buildGraph(
            Long spaceId,
            Long rootPageId,
            Integer depth,
            List<WikiPage> pages,
            List<WikiPageLink> links,
            Set<Long> includedNodeIds
    ) {
        Map<Long, WikiPage> pagesById = new HashMap<>();
        for (WikiPage page : pages) {
            pagesById.put(page.getId(), page);
        }

        List<WikiGraphNodeResponse> nodes = pages.stream()
                .filter(page -> includedNodeIds == null || includedNodeIds.contains(page.getId()))
                .sorted(Comparator
                        .comparing((WikiPage page) -> !Objects.equals(page.getId(), rootPageId))
                        .thenComparing(WikiPage::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(WikiPage::getId))
                .map(page -> WikiGraphNodeResponse.builder()
                        .id(page.getId())
                        .spaceId(page.getSpaceId())
                        .title(page.getTitle())
                        .status(page.getStatus())
                        .indexStatus(page.getIndexStatus())
                        .root(Objects.equals(page.getId(), rootPageId))
                        .build())
                .toList();

        Set<Long> nodeIds = nodes.stream().map(WikiGraphNodeResponse::getId).collect(Collectors.toSet());

        List<WikiGraphEdgeResponse> edges = links.stream()
                .filter(link -> link.getRelationStatus() == WikiPageLinkStatus.RESOLVED)
                .filter(link -> link.getTargetPageId() != null)
                .filter(link -> !Objects.equals(link.getSourcePageId(), link.getTargetPageId()))
                .filter(link -> nodeIds.contains(link.getSourcePageId()) && nodeIds.contains(link.getTargetPageId()))
                .map(link -> WikiGraphEdgeResponse.builder()
                        .sourcePageId(link.getSourcePageId())
                        .sourcePageTitle(resolveTitle(pagesById, link.getSourcePageId()))
                        .targetPageId(link.getTargetPageId())
                        .targetPageTitle(resolveTitle(pagesById, link.getTargetPageId()))
                        .mentionCount(link.getMentionCount())
                        .build())
                .toList();

        List<WikiGraphUnresolvedLinkResponse> unresolvedLinks = links.stream()
                .filter(link -> link.getRelationStatus() != WikiPageLinkStatus.RESOLVED)
                .filter(link -> nodeIds.contains(link.getSourcePageId()))
                .map(link -> WikiGraphUnresolvedLinkResponse.builder()
                        .sourcePageId(link.getSourcePageId())
                        .sourcePageTitle(resolveTitle(pagesById, link.getSourcePageId()))
                        .targetTitle(link.getTargetTitle())
                        .relationStatus(link.getRelationStatus())
                        .mentionCount(link.getMentionCount())
                        .build())
                .toList();

        return WikiGraphResponse.builder()
                .spaceId(spaceId)
                .rootPageId(rootPageId)
                .depth(depth)
                .nodeCount(nodes.size())
                .edgeCount(edges.size())
                .nodes(nodes)
                .edges(edges)
                .unresolvedLinks(unresolvedLinks)
                .build();
    }

    private String resolveTitle(Map<Long, WikiPage> pagesById, Long pageId) {
        WikiPage page = pagesById.get(pageId);
        return page == null ? null : page.getTitle();
    }

    private record PageDepth(Long pageId, int depth) {
    }
}
