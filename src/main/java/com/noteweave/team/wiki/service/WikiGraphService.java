package com.noteweave.team.wiki.service;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.permission.service.ResourceAccessService;
import com.noteweave.team.wiki.dto.WikiGraphEdgeResponse;
import com.noteweave.team.wiki.dto.WikiGraphNodeResponse;
import com.noteweave.team.wiki.dto.WikiGraphResponse;
import com.noteweave.team.wiki.dto.WikiGraphSummaryResponse;
import com.noteweave.team.wiki.dto.WikiGraphUnresolvedLinkResponse;
import com.noteweave.team.wiki.model.WikiPage;
import com.noteweave.team.wiki.model.WikiPageCitation;
import com.noteweave.team.wiki.model.WikiPageLink;
import com.noteweave.team.wiki.model.WikiPageLinkStatus;
import com.noteweave.team.wiki.model.WikiPageStatus;
import com.noteweave.team.wiki.model.WikiIndexStatus;
import com.noteweave.team.wiki.repository.WikiPageCitationRepository;
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
    private final WikiPageCitationRepository wikiPageCitationRepository;
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
        Map<Long, NodeCounts> countsByPageId = buildNodeCounts(pages, links);

        List<WikiGraphNodeResponse> nodes = pages.stream()
                .filter(page -> includedNodeIds == null || includedNodeIds.contains(page.getId()))
                .sorted(Comparator
                        .comparing((WikiPage page) -> !Objects.equals(page.getId(), rootPageId))
                        .thenComparing(WikiPage::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(WikiPage::getId))
                .map(page -> toNodeResponse(page, rootPageId, countsByPageId.getOrDefault(page.getId(), NodeCounts.empty())))
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
                .summary(buildSummary(pages, links, countsByPageId))
                .nodes(nodes)
                .edges(edges)
                .unresolvedLinks(unresolvedLinks)
                .build();
    }

    private Map<Long, NodeCounts> buildNodeCounts(List<WikiPage> pages, List<WikiPageLink> links) {
        Map<Long, NodeCounts> countsByPageId = new HashMap<>();
        for (WikiPage page : pages) {
            countsByPageId.put(page.getId(), NodeCounts.empty());
        }
        Map<Long, Set<Long>> neighbors = new HashMap<>();
        for (WikiPageLink link : links) {
            NodeCounts sourceCounts = countsByPageId.computeIfAbsent(link.getSourcePageId(), ignored -> NodeCounts.empty());
            if (link.getRelationStatus() == WikiPageLinkStatus.RESOLVED && link.getTargetPageId() != null) {
                sourceCounts.outgoingResolvedCount++;
                NodeCounts targetCounts = countsByPageId.computeIfAbsent(link.getTargetPageId(), ignored -> NodeCounts.empty());
                targetCounts.incomingResolvedCount++;
                neighbors.computeIfAbsent(link.getSourcePageId(), ignored -> new LinkedHashSet<>()).add(link.getTargetPageId());
                neighbors.computeIfAbsent(link.getTargetPageId(), ignored -> new LinkedHashSet<>()).add(link.getSourcePageId());
            } else {
                sourceCounts.unresolvedOutgoingCount++;
            }
        }

        Map<Long, Long> publishedVersionByPageId = pages.stream()
                .filter(page -> page.getPublishedVersionId() != null)
                .collect(Collectors.toMap(WikiPage::getId, WikiPage::getPublishedVersionId));
        if (!publishedVersionByPageId.isEmpty()) {
            for (WikiPageCitation citation : wikiPageCitationRepository.findByWikiPageIdInOrderByWikiPageIdAscIdAsc(publishedVersionByPageId.keySet())) {
                Long currentVersionId = publishedVersionByPageId.get(citation.getWikiPageId());
                if (Objects.equals(currentVersionId, citation.getWikiPageVersionId())) {
                    countsByPageId.computeIfAbsent(citation.getWikiPageId(), ignored -> NodeCounts.empty()).evidenceCitationCount++;
                }
            }
        }

        for (Map.Entry<Long, Set<Long>> entry : neighbors.entrySet()) {
            countsByPageId.computeIfAbsent(entry.getKey(), ignored -> NodeCounts.empty()).neighborCount = entry.getValue().size();
        }
        return countsByPageId;
    }

    private WikiGraphNodeResponse toNodeResponse(WikiPage page, Long rootPageId, NodeCounts counts) {
        return WikiGraphNodeResponse.builder()
                .id(page.getId())
                .spaceId(page.getSpaceId())
                .title(page.getTitle())
                .status(page.getStatus())
                .indexStatus(page.getIndexStatus())
                .root(Objects.equals(page.getId(), rootPageId))
                .outgoingResolvedCount(counts.outgoingResolvedCount)
                .incomingResolvedCount(counts.incomingResolvedCount)
                .unresolvedOutgoingCount(counts.unresolvedOutgoingCount)
                .neighborCount(counts.neighborCount)
                .evidenceCitationCount(counts.evidenceCitationCount)
                .linkCount(counts.outgoingResolvedCount + counts.incomingResolvedCount)
                .orphan(counts.incomingResolvedCount == 0)
                .leaf(counts.outgoingResolvedCount == 0 && counts.unresolvedOutgoingCount == 0)
                .sourceType(resolveSourceType(page))
                .sourceId(resolveSourceId(page))
                .autoMaintained(page.isAutoMaintained())
                .build();
    }

    private WikiGraphSummaryResponse buildSummary(List<WikiPage> pages, List<WikiPageLink> links, Map<Long, NodeCounts> countsByPageId) {
        int resolvedEdgeCount = 0;
        int missingLinkCount = 0;
        int ambiguousLinkCount = 0;
        int unresolvedLinkCount = 0;
        for (WikiPageLink link : links) {
            if (link.getRelationStatus() == WikiPageLinkStatus.RESOLVED && link.getTargetPageId() != null) {
                resolvedEdgeCount++;
            } else {
                unresolvedLinkCount++;
                if (link.getRelationStatus() == WikiPageLinkStatus.MISSING) {
                    missingLinkCount++;
                }
                if (link.getRelationStatus() == WikiPageLinkStatus.AMBIGUOUS) {
                    ambiguousLinkCount++;
                }
            }
        }

        int publishedPageCount = 0;
        int draftPageCount = 0;
        int indexedPageCount = 0;
        int orphanPageCount = 0;
        int leafPageCount = 0;
        int evidenceBackedPageCount = 0;
        int sourceBackedPageCount = 0;
        for (WikiPage page : pages) {
            NodeCounts counts = countsByPageId.getOrDefault(page.getId(), NodeCounts.empty());
            if (page.getStatus() == WikiPageStatus.PUBLISHED) {
                publishedPageCount++;
            }
            if (page.getStatus() == WikiPageStatus.DRAFT) {
                draftPageCount++;
            }
            if (page.getIndexStatus() == WikiIndexStatus.INDEXED) {
                indexedPageCount++;
            }
            if (counts.incomingResolvedCount == 0) {
                orphanPageCount++;
            }
            if (counts.outgoingResolvedCount == 0 && counts.unresolvedOutgoingCount == 0) {
                leafPageCount++;
            }
            if (counts.evidenceCitationCount > 0) {
                evidenceBackedPageCount++;
            }
            if (page.getSourceArtifactId() != null
                    || page.getSourceMessageId() != null
                    || page.getSourceDocumentId() != null
                    || page.getSourcePersonalSourceId() != null) {
                sourceBackedPageCount++;
            }
        }

        return WikiGraphSummaryResponse.builder()
                .totalPageCount(pages.size())
                .publishedPageCount(publishedPageCount)
                .draftPageCount(draftPageCount)
                .indexedPageCount(indexedPageCount)
                .resolvedEdgeCount(resolvedEdgeCount)
                .unresolvedLinkCount(unresolvedLinkCount)
                .missingLinkCount(missingLinkCount)
                .ambiguousLinkCount(ambiguousLinkCount)
                .orphanPageCount(orphanPageCount)
                .leafPageCount(leafPageCount)
                .evidenceBackedPageCount(evidenceBackedPageCount)
                .sourceBackedPageCount(sourceBackedPageCount)
                .build();
    }

    private String resolveSourceType(WikiPage page) {
        if (page.getSourceArtifactId() != null) {
            return "ARTIFACT";
        }
        if (page.getSourceMessageId() != null) {
            return "CHAT_MESSAGE";
        }
        if (page.getSourceDocumentId() != null) {
            return "DOCUMENT";
        }
        if (page.getSourcePersonalSourceId() != null) {
            return "SOURCE";
        }
        return "MANUAL";
    }

    private Long resolveSourceId(WikiPage page) {
        if (page.getSourceArtifactId() != null) {
            return page.getSourceArtifactId();
        }
        if (page.getSourceMessageId() != null) {
            return page.getSourceMessageId();
        }
        if (page.getSourceDocumentId() != null) {
            return page.getSourceDocumentId();
        }
        if (page.getSourcePersonalSourceId() != null) {
            return page.getSourcePersonalSourceId();
        }
        return null;
    }

    private String resolveTitle(Map<Long, WikiPage> pagesById, Long pageId) {
        WikiPage page = pagesById.get(pageId);
        return page == null ? null : page.getTitle();
    }

    private record PageDepth(Long pageId, int depth) {
    }

    private static class NodeCounts {
        private int outgoingResolvedCount;
        private int incomingResolvedCount;
        private int unresolvedOutgoingCount;
        private int neighborCount;
        private int evidenceCitationCount;

        private static NodeCounts empty() {
            return new NodeCounts();
        }
    }
}
