package com.noteweave.team.wiki.service;

import com.noteweave.permission.service.ResourceAccessService;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.team.wiki.dto.WikiPageAmbiguousCandidateResponse;
import com.noteweave.team.wiki.dto.WikiPageNeighborResponse;
import com.noteweave.team.wiki.dto.WikiPageRelationLinkResponse;
import com.noteweave.team.wiki.dto.WikiPageRelationSummaryResponse;
import com.noteweave.team.wiki.dto.WikiPageRelationsResponse;
import com.noteweave.team.wiki.dto.WikiPageUnresolvedLinkDetailResponse;
import com.noteweave.team.wiki.model.WikiPage;
import com.noteweave.team.wiki.model.WikiPageLink;
import com.noteweave.team.wiki.model.WikiPageLinkStatus;
import com.noteweave.team.wiki.repository.WikiPageLinkRepository;
import com.noteweave.team.wiki.repository.WikiPageRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
public class WikiRelationService {

    private final WikiPageRepository wikiPageRepository;
    private final WikiPageLinkRepository wikiPageLinkRepository;
    private final WikiLinkParser wikiLinkParser;
    private final ResourceAccessService resourceAccessService;

    @Transactional(readOnly = true)
    public WikiPageRelationsResponse getPageRelations(Long userId, Long pageId) {
        WikiPage page = getRequiredReadablePage(userId, pageId);
        Long spaceId = page.getSpaceId();
        List<WikiPage> pages = wikiPageRepository.findBySpaceIdAndDeletedAtIsNullOrderByUpdatedAtDesc(spaceId);
        List<WikiPageLink> outgoing = wikiPageLinkRepository.findBySourcePageIdOrderByIdAsc(pageId);
        List<WikiPageLink> incoming = wikiPageLinkRepository.findByTargetPageIdAndRelationStatusOrderByIdAsc(pageId, WikiPageLinkStatus.RESOLVED);
        Map<Long, WikiPage> pagesById = pages.stream().collect(Collectors.toMap(WikiPage::getId, value -> value));
        Map<String, List<WikiPage>> pagesByLookupTitle = pages.stream()
                .filter(candidate -> wikiLinkParser.normalizeLookupKey(candidate.getTitle()) != null)
                .collect(Collectors.groupingBy(candidate -> wikiLinkParser.normalizeLookupKey(candidate.getTitle())));

        List<WikiPageRelationLinkResponse> outgoingLinks = outgoing.stream()
                .filter(link -> link.getRelationStatus() == WikiPageLinkStatus.RESOLVED)
                .filter(link -> link.getTargetPageId() != null)
                .map(link -> WikiPageRelationLinkResponse.builder()
                        .pageId(link.getTargetPageId())
                        .title(resolveTitle(pagesById, link.getTargetPageId(), link.getTargetTitle()))
                        .mentionCount(link.getMentionCount())
                        .build())
                .sorted(Comparator.comparing(WikiPageRelationLinkResponse::getTitle, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();

        List<WikiPageRelationLinkResponse> incomingLinks = incoming.stream()
                .map(link -> WikiPageRelationLinkResponse.builder()
                        .pageId(link.getSourcePageId())
                        .title(resolveTitle(pagesById, link.getSourcePageId(), null))
                        .mentionCount(link.getMentionCount())
                        .build())
                .sorted(Comparator.comparing(WikiPageRelationLinkResponse::getTitle, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();

        List<WikiPageUnresolvedLinkDetailResponse> unresolvedLinks = outgoing.stream()
                .filter(link -> link.getRelationStatus() != WikiPageLinkStatus.RESOLVED)
                .map(link -> WikiPageUnresolvedLinkDetailResponse.builder()
                        .targetTitle(link.getTargetTitle())
                        .relationStatus(link.getRelationStatus())
                        .mentionCount(link.getMentionCount())
                        .candidatePages(resolveCandidates(pagesByLookupTitle, link.getTargetTitle()))
                        .build())
                .sorted(Comparator.comparing(WikiPageUnresolvedLinkDetailResponse::getTargetTitle, String::compareToIgnoreCase))
                .toList();

        List<WikiPageNeighborResponse> neighborPages = buildNeighbors(outgoing, incoming, pagesById);

        return WikiPageRelationsResponse.builder()
                .pageId(page.getId())
                .pageTitle(page.getTitle())
                .summary(toSummary(outgoing, incoming))
                .outgoingLinks(outgoingLinks)
                .incomingLinks(incomingLinks)
                .unresolvedLinks(unresolvedLinks)
                .neighborPages(neighborPages)
                .build();
    }

    @Transactional(readOnly = true)
    public Map<Long, WikiPageRelationSummaryResponse> summarizePagesForSpace(Long userId, Long spaceId, Set<Long> pageIds) {
        resourceAccessService.requireViewSpace(userId, spaceId);
        if (pageIds == null || pageIds.isEmpty()) {
            return Map.of();
        }
        List<WikiPageLink> links = wikiPageLinkRepository.findBySpaceIdOrderBySourcePageIdAscIdAsc(spaceId);

        Map<Long, Integer> outgoingResolved = new HashMap<>();
        Map<Long, Integer> incomingResolved = new HashMap<>();
        Map<Long, Integer> unresolvedOutgoing = new HashMap<>();
        Map<Long, Set<Long>> neighbors = new HashMap<>();

        for (WikiPageLink link : links) {
            Long sourceId = link.getSourcePageId();
            if (link.getRelationStatus() == WikiPageLinkStatus.RESOLVED && link.getTargetPageId() != null) {
                Long targetId = link.getTargetPageId();
                outgoingResolved.merge(sourceId, 1, Integer::sum);
                incomingResolved.merge(targetId, 1, Integer::sum);
                neighbors.computeIfAbsent(sourceId, ignored -> new java.util.LinkedHashSet<>()).add(targetId);
                neighbors.computeIfAbsent(targetId, ignored -> new java.util.LinkedHashSet<>()).add(sourceId);
            } else {
                unresolvedOutgoing.merge(sourceId, 1, Integer::sum);
            }
        }

        Map<Long, WikiPageRelationSummaryResponse> result = new HashMap<>();
        for (Long pageId : pageIds) {
            result.put(pageId, WikiPageRelationSummaryResponse.builder()
                    .outgoingResolvedCount(outgoingResolved.getOrDefault(pageId, 0))
                    .incomingResolvedCount(incomingResolved.getOrDefault(pageId, 0))
                    .unresolvedOutgoingCount(unresolvedOutgoing.getOrDefault(pageId, 0))
                    .neighborCount(neighbors.getOrDefault(pageId, Set.of()).size())
                    .build());
        }
        return result;
    }

    private WikiPageRelationSummaryResponse toSummary(List<WikiPageLink> outgoing, List<WikiPageLink> incoming) {
        int outgoingResolvedCount = 0;
        int unresolvedOutgoingCount = 0;
        Map<Long, Boolean> neighbors = new LinkedHashMap<>();
        for (WikiPageLink link : outgoing) {
            if (link.getRelationStatus() == WikiPageLinkStatus.RESOLVED && link.getTargetPageId() != null) {
                outgoingResolvedCount++;
                neighbors.put(link.getTargetPageId(), Boolean.TRUE);
            } else {
                unresolvedOutgoingCount++;
            }
        }
        for (WikiPageLink link : incoming) {
            neighbors.put(link.getSourcePageId(), Boolean.TRUE);
        }
        return WikiPageRelationSummaryResponse.builder()
                .outgoingResolvedCount(outgoingResolvedCount)
                .incomingResolvedCount(incoming.size())
                .unresolvedOutgoingCount(unresolvedOutgoingCount)
                .neighborCount(neighbors.size())
                .build();
    }

    private List<WikiPageNeighborResponse> buildNeighbors(
            List<WikiPageLink> outgoing,
            List<WikiPageLink> incoming,
            Map<Long, WikiPage> pagesById
    ) {
        Map<Long, Integer> outgoingByPage = new HashMap<>();
        for (WikiPageLink link : outgoing) {
            if (link.getRelationStatus() == WikiPageLinkStatus.RESOLVED && link.getTargetPageId() != null) {
                outgoingByPage.merge(link.getTargetPageId(), link.getMentionCount(), Integer::sum);
            }
        }

        Map<Long, Integer> incomingByPage = new HashMap<>();
        for (WikiPageLink link : incoming) {
            incomingByPage.merge(link.getSourcePageId(), link.getMentionCount(), Integer::sum);
        }

        List<WikiPageNeighborResponse> neighbors = new ArrayList<>();
        for (Long neighborId : unionIds(outgoingByPage.keySet(), incomingByPage.keySet())) {
            neighbors.add(WikiPageNeighborResponse.builder()
                    .pageId(neighborId)
                    .title(resolveTitle(pagesById, neighborId, null))
                    .outgoingMentionCount(outgoingByPage.getOrDefault(neighborId, 0))
                    .incomingMentionCount(incomingByPage.getOrDefault(neighborId, 0))
                    .bidirectional(outgoingByPage.containsKey(neighborId) && incomingByPage.containsKey(neighborId))
                    .build());
        }

        return neighbors.stream()
                .sorted(Comparator
                        .comparing(WikiPageNeighborResponse::isBidirectional, Comparator.reverseOrder())
                        .thenComparing((WikiPageNeighborResponse item) -> item.getOutgoingMentionCount() + item.getIncomingMentionCount(), Comparator.reverseOrder())
                        .thenComparing(WikiPageNeighborResponse::getTitle, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    private List<WikiPageAmbiguousCandidateResponse> resolveCandidates(Map<String, List<WikiPage>> pagesByLookupTitle, String targetTitle) {
        return pagesByLookupTitle.getOrDefault(wikiLinkParser.normalizeLookupKey(targetTitle), List.of()).stream()
                .map(page -> WikiPageAmbiguousCandidateResponse.builder()
                        .pageId(page.getId())
                        .title(page.getTitle())
                        .status(page.getStatus())
                        .indexStatus(page.getIndexStatus())
                        .build())
                .sorted(Comparator.comparing(WikiPageAmbiguousCandidateResponse::getPageId))
                .toList();
    }

    private String resolveTitle(Map<Long, WikiPage> pagesById, Long pageId, String fallback) {
        WikiPage page = pagesById.get(pageId);
        return page == null ? fallback : page.getTitle();
    }

    private Set<Long> unionIds(Set<Long> left, Set<Long> right) {
        java.util.LinkedHashSet<Long> merged = new java.util.LinkedHashSet<>(left);
        merged.addAll(right);
        return merged;
    }

    private WikiPage getRequiredReadablePage(Long userId, Long pageId) {
        WikiPage page = wikiPageRepository.findByIdAndDeletedAtIsNull(pageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WIKI_PAGE_NOT_FOUND));
        if (!resourceAccessService.canViewSpace(userId, page.getSpaceId())) {
            throw new BusinessException(ErrorCode.WIKI_ACCESS_DENIED, "No permission to view wiki");
        }
        return page;
    }
}
