package com.noteweave.graph.service;

import com.noteweave.artifact.model.Artifact;
import com.noteweave.artifact.model.ArtifactSource;
import com.noteweave.artifact.model.ArtifactSourceType;
import com.noteweave.artifact.model.SessionArtifact;
import com.noteweave.artifact.repository.ArtifactCardRelationRepository;
import com.noteweave.artifact.repository.ArtifactCitationRepository;
import com.noteweave.artifact.repository.ArtifactRepository;
import com.noteweave.artifact.repository.ArtifactSourceRepository;
import com.noteweave.artifact.repository.SessionArtifactRepository;
import com.noteweave.chat.model.ChatMessage;
import com.noteweave.chat.repository.ChatMessageRepository;
import com.noteweave.citation.model.Citation;
import com.noteweave.citation.model.MessageCitation;
import com.noteweave.citation.repository.CitationRepository;
import com.noteweave.citation.repository.MessageCitationRepository;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.graph.dto.KnowledgeGraphEdgeResponse;
import com.noteweave.graph.dto.KnowledgeGraphEdgeType;
import com.noteweave.graph.dto.KnowledgeGraphEdgeView;
import com.noteweave.graph.dto.KnowledgeGraphFilterRequest;
import com.noteweave.graph.dto.KnowledgeGraphNodeDetailResponse;
import com.noteweave.graph.dto.KnowledgeGraphNodeResponse;
import com.noteweave.graph.dto.KnowledgeGraphNodeType;
import com.noteweave.graph.dto.KnowledgeGraphPathResponse;
import com.noteweave.graph.dto.KnowledgeGraphResponse;
import com.noteweave.permission.service.ResourceAccessService;
import com.noteweave.personal.card.model.ArticleCard;
import com.noteweave.personal.card.model.ArticleCardCitation;
import com.noteweave.personal.card.model.ArticleConceptRelation;
import com.noteweave.personal.card.model.ConceptCard;
import com.noteweave.personal.card.model.ConceptCardCitation;
import com.noteweave.personal.card.model.ConceptRelation;
import com.noteweave.personal.card.model.SynthesisCard;
import com.noteweave.personal.card.model.SynthesisCardCitation;
import com.noteweave.personal.card.model.SynthesisConceptRelation;
import com.noteweave.personal.card.repository.ArticleCardCitationRepository;
import com.noteweave.personal.card.repository.ArticleCardRepository;
import com.noteweave.personal.card.repository.ArticleConceptRelationRepository;
import com.noteweave.personal.card.repository.ConceptCardCitationRepository;
import com.noteweave.personal.card.repository.ConceptCardRepository;
import com.noteweave.personal.card.repository.ConceptRelationRepository;
import com.noteweave.personal.card.repository.SynthesisCardCitationRepository;
import com.noteweave.personal.card.repository.SynthesisCardRepository;
import com.noteweave.personal.card.repository.SynthesisConceptRelationRepository;
import com.noteweave.personal.methodology.model.MethodologyCard;
import com.noteweave.personal.methodology.model.MethodologyCardStatus;
import com.noteweave.personal.methodology.repository.MethodologyCardRepository;
import com.noteweave.team.document.model.Document;
import com.noteweave.team.document.model.DocumentStatus;
import com.noteweave.team.document.repository.DocumentRepository;
import com.noteweave.team.wiki.model.WikiPage;
import com.noteweave.team.wiki.model.WikiPageCitation;
import com.noteweave.team.wiki.model.WikiPageLink;
import com.noteweave.team.wiki.model.WikiPageLinkStatus;
import com.noteweave.team.wiki.repository.WikiPageCitationRepository;
import com.noteweave.team.wiki.repository.WikiPageLinkRepository;
import com.noteweave.team.wiki.repository.WikiPageRepository;
import com.noteweave.team.wiki.service.TeamWikiService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
public class KnowledgeGraphService {

    private static final int MAX_NEIGHBOR_DEPTH = 4;

    private final ResourceAccessService resourceAccessService;
    private final TeamWikiService teamWikiService;
    private final WikiPageRepository wikiPageRepository;
    private final WikiPageCitationRepository wikiPageCitationRepository;
    private final WikiPageLinkRepository wikiPageLinkRepository;
    private final ArtifactRepository artifactRepository;
    private final ArtifactCardRelationRepository artifactCardRelationRepository;
    private final ArtifactSourceRepository artifactSourceRepository;
    private final ArtifactCitationRepository artifactCitationRepository;
    private final SessionArtifactRepository sessionArtifactRepository;
    private final DocumentRepository documentRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final MessageCitationRepository messageCitationRepository;
    private final CitationRepository citationRepository;
    private final ArticleCardRepository articleCardRepository;
    private final ConceptCardRepository conceptCardRepository;
    private final SynthesisCardRepository synthesisCardRepository;
    private final MethodologyCardRepository methodologyCardRepository;
    private final ArticleConceptRelationRepository articleConceptRelationRepository;
    private final ConceptRelationRepository conceptRelationRepository;
    private final SynthesisConceptRelationRepository synthesisConceptRelationRepository;
    private final ArticleCardCitationRepository articleCardCitationRepository;
    private final ConceptCardCitationRepository conceptCardCitationRepository;
    private final SynthesisCardCitationRepository synthesisCardCitationRepository;

    @Transactional(readOnly = true)
    public KnowledgeGraphResponse getSpaceGraph(Long userId, Long spaceId, KnowledgeGraphFilterRequest filter) {
        resourceAccessService.requireViewSpace(userId, spaceId);
        KnowledgeGraphData data = buildSpaceGraph(spaceId);
        return toGraphResponse(applyFilter(data, normalizeFilter(filter)), null);
    }

    @Transactional(readOnly = true)
    public KnowledgeGraphResponse getWikiNeighborhood(Long userId, Long wikiPageId, Integer depth, KnowledgeGraphFilterRequest filter) {
        WikiPage page = teamWikiService.getRequiredReadablePage(userId, wikiPageId);
        String rootNodeId = nodeId(KnowledgeGraphNodeType.WIKI_PAGE, page.getId());
        return getNeighborhood(userId, page.getSpaceId(), rootNodeId, depth, filter);
    }

    @Transactional(readOnly = true)
    public KnowledgeGraphResponse getNeighborhood(Long userId, Long spaceId, String nodeId, Integer depth, KnowledgeGraphFilterRequest filter) {
        resourceAccessService.requireViewSpace(userId, spaceId);
        validateNodeId(nodeId);
        int normalizedDepth = normalizeDepth(depth);
        KnowledgeGraphData data = applyFilter(buildSpaceGraph(spaceId), normalizeFilter(filter));
        ensureNodeExists(data, nodeId);

        Set<String> includedIds = collectNeighborhoodNodeIds(data, nodeId, normalizedDepth);
        Map<String, KnowledgeGraphNodeResponse> nodes = data.getNodesById().entrySet().stream()
                .filter(entry -> includedIds.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (left, right) -> left, LinkedHashMap::new));
        List<KnowledgeGraphEdgeResponse> edges = data.getEdges().stream()
                .filter(edge -> includedIds.contains(edge.getSourceId()) && includedIds.contains(edge.getTargetId()))
                .toList();
        return toGraphResponse(KnowledgeGraphData.builder().spaceId(spaceId).nodesById(nodes).edges(edges).build(), nodeId);
    }

    @Transactional(readOnly = true)
    public KnowledgeGraphNodeDetailResponse getNodeDetail(Long userId, Long spaceId, String nodeId, KnowledgeGraphFilterRequest filter) {
        resourceAccessService.requireViewSpace(userId, spaceId);
        validateNodeId(nodeId);
        KnowledgeGraphData data = applyFilter(buildSpaceGraph(spaceId), normalizeFilter(filter));
        KnowledgeGraphNodeResponse node = ensureNodeExists(data, nodeId);

        List<KnowledgeGraphEdgeResponse> adjacentEdges = data.getEdges().stream()
                .filter(edge -> Objects.equals(edge.getSourceId(), nodeId) || Objects.equals(edge.getTargetId(), nodeId))
                .sorted(Comparator.comparing(KnowledgeGraphEdgeResponse::getType))
                .toList();

        return KnowledgeGraphNodeDetailResponse.builder()
                .id(node.getId())
                .refId(node.getRefId())
                .spaceId(node.getSpaceId())
                .type(node.getType())
                .title(node.getTitle())
                .subtitle(node.getSubtitle())
                .status(node.getStatus())
                .attributes(buildAttributes(node))
                .adjacentEdges(adjacentEdges)
                .build();
    }

    @Transactional(readOnly = true)
    public KnowledgeGraphPathResponse findShortestPath(
            Long userId,
            Long spaceId,
            String sourceNodeId,
            String targetNodeId,
            KnowledgeGraphEdgeView edgeView,
            KnowledgeGraphFilterRequest filter
    ) {
        resourceAccessService.requireViewSpace(userId, spaceId);
        validateNodeId(sourceNodeId);
        validateNodeId(targetNodeId);
        KnowledgeGraphData data = applyFilter(buildSpaceGraph(spaceId), normalizeFilter(filter));
        ensureNodeExists(data, sourceNodeId);
        ensureNodeExists(data, targetNodeId);

        List<String> nodePath = shortestPath(data, sourceNodeId, targetNodeId, edgeView == null ? KnowledgeGraphEdgeView.UNDIRECTED : edgeView);
        if (nodePath.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "no path found between graph nodes");
        }
        List<KnowledgeGraphNodeResponse> nodes = nodePath.stream().map(data.getNodesById()::get).toList();
        List<KnowledgeGraphEdgeResponse> edges = edgesForPath(data.getEdges(), nodePath, edgeView == null ? KnowledgeGraphEdgeView.UNDIRECTED : edgeView);
        return KnowledgeGraphPathResponse.builder()
                .spaceId(spaceId)
                .sourceNodeId(sourceNodeId)
                .targetNodeId(targetNodeId)
                .length(Math.max(nodePath.size() - 1, 0))
                .nodes(nodes)
                .edges(edges)
                .build();
    }

    private KnowledgeGraphData buildSpaceGraph(Long spaceId) {
        List<WikiPage> wikiPages = wikiPageRepository.findBySpaceIdAndDeletedAtIsNullOrderByUpdatedAtDesc(spaceId);
        List<WikiPageLink> wikiLinks = wikiPageLinkRepository.findBySpaceIdOrderBySourcePageIdAscIdAsc(spaceId);
        List<Artifact> artifacts = artifactRepository.findBySpaceIdAndDeletedAtIsNullOrderByUpdatedAtDesc(spaceId);
        List<Document> documents = loadDocumentsForSpace(spaceId);
        List<ChatMessage> chatMessages = loadChatMessagesForSpace(wikiPages, artifacts);
        List<ArticleCard> articleCards = articleCardRepository.findAll().stream()
                .filter(card -> Objects.equals(card.getSpaceId(), spaceId))
                .toList();
        List<ConceptCard> conceptCards = conceptCardRepository.findAll().stream()
                .filter(card -> Objects.equals(card.getSpaceId(), spaceId))
                .toList();
        List<SynthesisCard> synthesisCards = synthesisCardRepository.findAll().stream()
                .filter(card -> Objects.equals(card.getSpaceId(), spaceId))
                .toList();
        List<MethodologyCard> methodologyCards = methodologyCardRepository.findAll().stream()
                .filter(card -> Objects.equals(card.getSpaceId(), spaceId))
                .filter(card -> card.getStatus() == MethodologyCardStatus.ACTIVE)
                .toList();

        Map<Long, Artifact> artifactById = indexById(artifacts);
        Map<Long, Document> documentById = indexById(documents);
        Map<Long, ChatMessage> messageById = indexById(chatMessages);

        LinkedHashMap<String, KnowledgeGraphNodeResponse> nodes = new LinkedHashMap<>();
        List<KnowledgeGraphEdgeResponse> edges = new ArrayList<>();

        for (WikiPage page : wikiPages) {
            nodes.put(nodeId(KnowledgeGraphNodeType.WIKI_PAGE, page.getId()), node(
                    KnowledgeGraphNodeType.WIKI_PAGE,
                    page.getId(),
                    spaceId,
                    page.getTitle(),
                    "Wiki",
                    String.valueOf(page.getStatus()),
                    String.valueOf(page.getIndexStatus()),
                    false
            ));
        }

        for (Artifact artifact : artifacts) {
            nodes.put(nodeId(KnowledgeGraphNodeType.ARTIFACT, artifact.getId()), node(
                    KnowledgeGraphNodeType.ARTIFACT,
                    artifact.getId(),
                    spaceId,
                    artifact.getTitle(),
                    String.valueOf(artifact.getArtifactType()),
                    String.valueOf(artifact.getStatus()),
                    null,
                    false
            ));
        }

        for (Document document : documents) {
            nodes.put(nodeId(KnowledgeGraphNodeType.DOCUMENT, document.getId()), node(
                    KnowledgeGraphNodeType.DOCUMENT,
                    document.getId(),
                    spaceId,
                    document.getTitle(),
                    "Document",
                    String.valueOf(document.getStatus()),
                    String.valueOf(document.getStatus()),
                    false
            ));
        }

        for (ChatMessage message : chatMessages) {
            nodes.put(nodeId(KnowledgeGraphNodeType.CHAT_MESSAGE, message.getId()), node(
                    KnowledgeGraphNodeType.CHAT_MESSAGE,
                    message.getId(),
                    spaceId,
                    buildMessageTitle(message),
                    String.valueOf(message.getRole()),
                    String.valueOf(message.getStatus()),
                    null,
                    false
            ));
        }

        for (ArticleCard card : articleCards) {
            nodes.put(nodeId(KnowledgeGraphNodeType.ARTICLE_CARD, card.getId()), node(
                    KnowledgeGraphNodeType.ARTICLE_CARD,
                    card.getId(),
                    spaceId,
                    card.getTitle(),
                    "Article Card",
                    String.valueOf(card.getCardStatus()),
                    null,
                    false
            ));
        }

        for (ConceptCard card : conceptCards) {
            nodes.put(nodeId(KnowledgeGraphNodeType.CONCEPT_CARD, card.getId()), node(
                    KnowledgeGraphNodeType.CONCEPT_CARD,
                    card.getId(),
                    spaceId,
                    card.getName(),
                    "Concept Card",
                    String.valueOf(card.getCardStatus()),
                    null,
                    false
            ));
        }

        for (SynthesisCard card : synthesisCards) {
            nodes.put(nodeId(KnowledgeGraphNodeType.SYNTHESIS_CARD, card.getId()), node(
                    KnowledgeGraphNodeType.SYNTHESIS_CARD,
                    card.getId(),
                    spaceId,
                    card.getTitle(),
                    "Synthesis Card",
                    String.valueOf(card.getCardStatus()),
                    null,
                    false
            ));
        }

        for (MethodologyCard card : methodologyCards) {
            nodes.put(nodeId(KnowledgeGraphNodeType.METHODOLOGY_CARD, card.getId()), node(
                    KnowledgeGraphNodeType.METHODOLOGY_CARD,
                    card.getId(),
                    spaceId,
                    card.getName(),
                    "Methodology Card",
                    String.valueOf(card.getStatus()),
                    null,
                    false
            ));
        }

        for (WikiPageLink link : wikiLinks) {
            if (link.getRelationStatus() == WikiPageLinkStatus.RESOLVED && link.getTargetPageId() != null) {
                edges.add(edge(nodeId(KnowledgeGraphNodeType.WIKI_PAGE, link.getSourcePageId()),
                        nodeId(KnowledgeGraphNodeType.WIKI_PAGE, link.getTargetPageId()),
                        KnowledgeGraphEdgeType.WIKI_LINK, "wiki-link", link.getMentionCount()));
            }
        }

        for (WikiPage page : wikiPages) {
            if (page.getSourceArtifactId() != null && artifactById.containsKey(page.getSourceArtifactId())) {
                edges.add(edge(nodeId(KnowledgeGraphNodeType.WIKI_PAGE, page.getId()),
                        nodeId(KnowledgeGraphNodeType.ARTIFACT, page.getSourceArtifactId()),
                        KnowledgeGraphEdgeType.WIKI_SOURCE_ARTIFACT, "published-from-artifact", 1));
            }
            if (page.getSourceMessageId() != null && messageById.containsKey(page.getSourceMessageId())) {
                edges.add(edge(nodeId(KnowledgeGraphNodeType.WIKI_PAGE, page.getId()),
                        nodeId(KnowledgeGraphNodeType.CHAT_MESSAGE, page.getSourceMessageId()),
                        KnowledgeGraphEdgeType.WIKI_SOURCE_MESSAGE, "published-from-message", 1));
            }
        }

        for (Artifact artifact : artifacts) {
            if (artifact.getCreatedFromMessageId() != null && messageById.containsKey(artifact.getCreatedFromMessageId())) {
                edges.add(edge(nodeId(KnowledgeGraphNodeType.CHAT_MESSAGE, artifact.getCreatedFromMessageId()),
                        nodeId(KnowledgeGraphNodeType.ARTIFACT, artifact.getId()),
                        KnowledgeGraphEdgeType.CHAT_GENERATES_ARTIFACT, "generates", 1));
            }

            for (ArtifactSource source : artifactSourceRepository.findByArtifactIdOrderByIdAsc(artifact.getId())) {
                KnowledgeGraphEdgeResponse mapped = mapArtifactSourceEdge(artifact.getId(), source, documentById, messageById, artifactById);
                if (mapped != null) {
                    edges.add(mapped);
                }
            }

            artifactCitationRepository.findByArtifactIdOrderByIdAsc(artifact.getId()).forEach(artifactCitation ->
                    citationRepository.findById(artifactCitation.getCitationId()).ifPresent(citation -> maybeAddDocumentEdge(
                            edges,
                            nodeId(KnowledgeGraphNodeType.ARTIFACT, artifact.getId()),
                            citation,
                            documentById,
                            KnowledgeGraphEdgeType.ARTIFACT_CITES_DOCUMENT,
                            "cites-document"
                    )));
        }

        for (WikiPage page : wikiPages) {
            if (page.getPublishedVersionId() == null) {
                continue;
            }
            for (WikiPageCitation relation : wikiPageCitationRepository.findByWikiPageIdAndWikiPageVersionIdOrderByIdAsc(page.getId(), page.getPublishedVersionId())) {
                citationRepository.findById(relation.getCitationId()).ifPresent(citation -> maybeAddDocumentEdge(
                        edges,
                        nodeId(KnowledgeGraphNodeType.WIKI_PAGE, page.getId()),
                        citation,
                        documentById,
                        KnowledgeGraphEdgeType.WIKI_CITES_DOCUMENT,
                        "wiki-cites-document"
                ));
            }
        }

        for (ChatMessage message : chatMessages) {
            for (MessageCitation relation : messageCitationRepository.findByMessageId(message.getId())) {
                citationRepository.findById(relation.getCitationId()).ifPresent(citation -> maybeAddDocumentEdge(
                        edges,
                        nodeId(KnowledgeGraphNodeType.CHAT_MESSAGE, message.getId()),
                        citation,
                        documentById,
                        KnowledgeGraphEdgeType.CHAT_CITES_DOCUMENT,
                        "chat-cites-document"
                ));
            }
        }

        for (Artifact artifact : artifacts) {
            if (artifact.getCreatedFromSessionId() == null || artifact.getCreatedFromMessageId() == null) {
                continue;
            }
            for (SessionArtifact relation : sessionArtifactRepository.findBySessionIdOrderByIdAsc(artifact.getCreatedFromSessionId())) {
                if (Objects.equals(relation.getArtifactId(), artifact.getId())) {
                    edges.add(edge(nodeId(KnowledgeGraphNodeType.CHAT_MESSAGE, artifact.getCreatedFromMessageId()),
                            nodeId(KnowledgeGraphNodeType.ARTIFACT, relation.getArtifactId()),
                            KnowledgeGraphEdgeType.ARTIFACT_IN_SESSION,
                            String.valueOf(relation.getRelationType()).toLowerCase(),
                            1));
                }
            }
        }

        for (ArticleCard card : articleCards) {
            for (ArticleConceptRelation relation : articleConceptRelationRepository.findByArticleCardIdOrderByIdAsc(card.getId())) {
                if (nodes.containsKey(nodeId(KnowledgeGraphNodeType.CONCEPT_CARD, relation.getConceptCardId()))) {
                    edges.add(edge(
                            nodeId(KnowledgeGraphNodeType.ARTICLE_CARD, card.getId()),
                            nodeId(KnowledgeGraphNodeType.CONCEPT_CARD, relation.getConceptCardId()),
                            KnowledgeGraphEdgeType.ARTICLE_RELATES_CONCEPT,
                            "article-relates-concept",
                            relation.getRelevanceScore() == null ? 1 : Math.max(1, relation.getRelevanceScore().movePointRight(2).intValue())
                    ));
                }
            }
            for (ArticleCardCitation citationRelation : articleCardCitationRepository.findByArticleCardIdOrderByIdAsc(card.getId())) {
                citationRepository.findById(citationRelation.getCitationId()).ifPresent(citation -> maybeAddDocumentEdge(
                        edges,
                        nodeId(KnowledgeGraphNodeType.ARTICLE_CARD, card.getId()),
                        citation,
                        documentById,
                        KnowledgeGraphEdgeType.ARTICLE_CITES_DOCUMENT,
                        "article-cites-document"
                ));
            }
        }

        for (ConceptCard card : conceptCards) {
            for (ConceptRelation relation : conceptRelationRepository.findByResearchProjectId(card.getResearchProjectId())) {
                if (Objects.equals(relation.getSourceConceptId(), card.getId())
                        && nodes.containsKey(nodeId(KnowledgeGraphNodeType.CONCEPT_CARD, relation.getTargetConceptId()))) {
                    edges.add(edge(
                            nodeId(KnowledgeGraphNodeType.CONCEPT_CARD, card.getId()),
                            nodeId(KnowledgeGraphNodeType.CONCEPT_CARD, relation.getTargetConceptId()),
                            KnowledgeGraphEdgeType.CONCEPT_RELATES_CONCEPT,
                            relation.getRelationType(),
                            1
                    ));
                }
            }
            for (ConceptCardCitation citationRelation : conceptCardCitationRepository.findByConceptCardIdOrderByIdAsc(card.getId())) {
                citationRepository.findById(citationRelation.getCitationId()).ifPresent(citation -> maybeAddDocumentEdge(
                        edges,
                        nodeId(KnowledgeGraphNodeType.CONCEPT_CARD, card.getId()),
                        citation,
                        documentById,
                        KnowledgeGraphEdgeType.CONCEPT_CITES_DOCUMENT,
                        "concept-cites-document"
                ));
            }
        }

        for (SynthesisCard card : synthesisCards) {
            if (artifactById.containsKey(card.getSourceArtifactId())) {
                edges.add(edge(
                        nodeId(KnowledgeGraphNodeType.SYNTHESIS_CARD, card.getId()),
                        nodeId(KnowledgeGraphNodeType.ARTIFACT, card.getSourceArtifactId()),
                        KnowledgeGraphEdgeType.SYNTHESIS_SOURCE_ARTIFACT,
                        "synthesis-source-artifact",
                        1
                ));
            }
            for (SynthesisConceptRelation relation : synthesisConceptRelationRepository.findBySynthesisCardIdOrderByIdAsc(card.getId())) {
                if (nodes.containsKey(nodeId(KnowledgeGraphNodeType.CONCEPT_CARD, relation.getConceptCardId()))) {
                    edges.add(edge(
                            nodeId(KnowledgeGraphNodeType.SYNTHESIS_CARD, card.getId()),
                            nodeId(KnowledgeGraphNodeType.CONCEPT_CARD, relation.getConceptCardId()),
                            KnowledgeGraphEdgeType.SYNTHESIS_RELATES_CONCEPT,
                            relation.getRelationType(),
                            1
                    ));
                }
            }
            for (SynthesisCardCitation citationRelation : synthesisCardCitationRepository.findBySynthesisCardIdOrderByIdAsc(card.getId())) {
                citationRepository.findById(citationRelation.getCitationId()).ifPresent(citation -> maybeAddDocumentEdge(
                        edges,
                        nodeId(KnowledgeGraphNodeType.SYNTHESIS_CARD, card.getId()),
                        citation,
                        documentById,
                        KnowledgeGraphEdgeType.SYNTHESIS_CITES_DOCUMENT,
                        "synthesis-cites-document"
                ));
            }
        }

        for (Artifact artifact : artifacts) {
            for (var relation : artifactCardRelationRepository.findByArtifactIdOrderByIdAsc(artifact.getId())) {
                KnowledgeGraphNodeType targetType = switch (relation.getCardType()) {
                    case CONCEPT -> KnowledgeGraphNodeType.CONCEPT_CARD;
                    case METHODOLOGY -> KnowledgeGraphNodeType.METHODOLOGY_CARD;
                    case SYNTHESIS -> KnowledgeGraphNodeType.SYNTHESIS_CARD;
                };
                String targetId = nodeId(targetType, relation.getCardId());
                if (nodes.containsKey(targetId)) {
                    edges.add(edge(
                            nodeId(KnowledgeGraphNodeType.ARTIFACT, artifact.getId()),
                            targetId,
                            KnowledgeGraphEdgeType.ARTIFACT_RELATES_CARD,
                            String.valueOf(relation.getRelationType()),
                            1
                    ));
                }
            }
        }

        return KnowledgeGraphData.builder()
                .spaceId(spaceId)
                .nodesById(nodes)
                .edges(dedupeEdges(edges))
                .build();
    }

    private KnowledgeGraphData applyFilter(KnowledgeGraphData data, KnowledgeGraphFilterRequest filter) {
        Map<String, KnowledgeGraphNodeResponse> nodes = new LinkedHashMap<>(data.getNodesById());

        if (!filter.getNodeTypes().isEmpty()) {
            nodes.entrySet().removeIf(entry -> !filter.getNodeTypes().contains(entry.getValue().getType()));
        }
        if (Boolean.TRUE.equals(filter.getOnlyPublished())) {
            nodes.entrySet().removeIf(entry -> entry.getValue().getType() == KnowledgeGraphNodeType.WIKI_PAGE && !"PUBLISHED".equals(entry.getValue().getStatus()));
            nodes.entrySet().removeIf(entry -> entry.getValue().getType() == KnowledgeGraphNodeType.ARTIFACT
                    && !Set.of("READY", "PUBLISHED_TO_WIKI", "DISTILLED_TO_PERSONAL_WIKI").contains(entry.getValue().getStatus()));
        }
        if (Boolean.TRUE.equals(filter.getOnlyIndexed())) {
            nodes.entrySet().removeIf(entry -> entry.getValue().getType() == KnowledgeGraphNodeType.WIKI_PAGE
                    && !"INDEXED".equals(entry.getValue().getIndexStatus()));
            nodes.entrySet().removeIf(entry -> entry.getValue().getType() == KnowledgeGraphNodeType.DOCUMENT
                    && !"INDEXED".equals(entry.getValue().getIndexStatus()));
        }

        Set<String> allowedNodeIds = nodes.keySet();
        List<KnowledgeGraphEdgeResponse> edges = data.getEdges().stream()
                .filter(edge -> allowedNodeIds.contains(edge.getSourceId()) && allowedNodeIds.contains(edge.getTargetId()))
                .filter(edge -> filter.getEdgeTypes().isEmpty() || filter.getEdgeTypes().contains(edge.getType()))
                .toList();

        Set<String> connectedNodeIds = new LinkedHashSet<>();
        for (KnowledgeGraphEdgeResponse edge : edges) {
            connectedNodeIds.add(edge.getSourceId());
            connectedNodeIds.add(edge.getTargetId());
        }
        if (!filter.getNodeTypes().isEmpty() || Boolean.TRUE.equals(filter.getOnlyPublished()) || Boolean.TRUE.equals(filter.getOnlyIndexed())) {
            nodes.entrySet().removeIf(entry -> !connectedNodeIds.contains(entry.getKey()));
        }

        return KnowledgeGraphData.builder()
                .spaceId(data.getSpaceId())
                .nodesById(nodes)
                .edges(edges)
                .build();
    }

    private KnowledgeGraphResponse toGraphResponse(KnowledgeGraphData data, String rootNodeId) {
        List<KnowledgeGraphNodeResponse> sortedNodes = data.getNodesById().values().stream()
                .map(node -> node.toBuilder().root(Objects.equals(rootNodeId, node.getId())).build())
                .sorted(Comparator
                        .comparing(KnowledgeGraphNodeResponse::isRoot, Comparator.reverseOrder())
                        .thenComparing(KnowledgeGraphNodeResponse::getType)
                        .thenComparing(KnowledgeGraphNodeResponse::getTitle, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
        return KnowledgeGraphResponse.builder()
                .spaceId(data.getSpaceId())
                .rootNodeId(rootNodeId)
                .nodeCount(sortedNodes.size())
                .edgeCount(data.getEdges().size())
                .nodes(sortedNodes)
                .edges(data.getEdges())
                .build();
    }

    private Set<String> collectNeighborhoodNodeIds(KnowledgeGraphData data, String rootNodeId, int depth) {
        Map<String, Set<String>> adjacency = new HashMap<>();
        for (KnowledgeGraphEdgeResponse edge : data.getEdges()) {
            adjacency.computeIfAbsent(edge.getSourceId(), ignored -> new LinkedHashSet<>()).add(edge.getTargetId());
            adjacency.computeIfAbsent(edge.getTargetId(), ignored -> new LinkedHashSet<>()).add(edge.getSourceId());
        }

        Set<String> visited = new LinkedHashSet<>();
        ArrayDeque<NodeDepth> queue = new ArrayDeque<>();
        visited.add(rootNodeId);
        queue.add(new NodeDepth(rootNodeId, 0));
        while (!queue.isEmpty()) {
            NodeDepth current = queue.removeFirst();
            if (current.depth() >= depth) {
                continue;
            }
            for (String neighborId : adjacency.getOrDefault(current.nodeId(), Set.of())) {
                if (visited.add(neighborId)) {
                    queue.addLast(new NodeDepth(neighborId, current.depth() + 1));
                }
            }
        }
        return visited;
    }

    private List<String> shortestPath(KnowledgeGraphData data, String sourceNodeId, String targetNodeId, KnowledgeGraphEdgeView edgeView) {
        Map<String, Set<String>> adjacency = new HashMap<>();
        for (KnowledgeGraphEdgeResponse edge : data.getEdges()) {
            adjacency.computeIfAbsent(edge.getSourceId(), ignored -> new LinkedHashSet<>()).add(edge.getTargetId());
            if (edgeView == KnowledgeGraphEdgeView.UNDIRECTED) {
                adjacency.computeIfAbsent(edge.getTargetId(), ignored -> new LinkedHashSet<>()).add(edge.getSourceId());
            }
        }

        Map<String, String> previous = new HashMap<>();
        Set<String> visited = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        visited.add(sourceNodeId);
        queue.add(sourceNodeId);

        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (Objects.equals(current, targetNodeId)) {
                break;
            }
            for (String neighbor : adjacency.getOrDefault(current, Set.of())) {
                if (visited.add(neighbor)) {
                    previous.put(neighbor, current);
                    queue.addLast(neighbor);
                }
            }
        }

        if (!visited.contains(targetNodeId)) {
            return List.of();
        }
        List<String> path = new ArrayList<>();
        String cursor = targetNodeId;
        while (cursor != null) {
            path.add(0, cursor);
            cursor = previous.get(cursor);
        }
        return path;
    }

    private List<KnowledgeGraphEdgeResponse> edgesForPath(List<KnowledgeGraphEdgeResponse> edges, List<String> nodePath, KnowledgeGraphEdgeView edgeView) {
        List<KnowledgeGraphEdgeResponse> result = new ArrayList<>();
        for (int i = 0; i < nodePath.size() - 1; i++) {
            String sourceId = nodePath.get(i);
            String targetId = nodePath.get(i + 1);
            for (KnowledgeGraphEdgeResponse edge : edges) {
                boolean direct = Objects.equals(edge.getSourceId(), sourceId) && Objects.equals(edge.getTargetId(), targetId);
                boolean reverse = edgeView == KnowledgeGraphEdgeView.UNDIRECTED
                        && Objects.equals(edge.getSourceId(), targetId)
                        && Objects.equals(edge.getTargetId(), sourceId);
                if (direct || reverse) {
                    result.add(edge);
                    break;
                }
            }
        }
        return result;
    }

    private KnowledgeGraphNodeResponse ensureNodeExists(KnowledgeGraphData data, String nodeId) {
        KnowledgeGraphNodeResponse node = data.getNodesById().get(nodeId);
        if (node == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "graph node not found");
        }
        return node;
    }

    private KnowledgeGraphFilterRequest normalizeFilter(KnowledgeGraphFilterRequest filter) {
        return filter == null ? new KnowledgeGraphFilterRequest() : filter;
    }

    private int normalizeDepth(Integer depth) {
        int value = depth == null ? 1 : depth;
        if (value < 0 || value > MAX_NEIGHBOR_DEPTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "depth must be between 0 and " + MAX_NEIGHBOR_DEPTH);
        }
        return value;
    }

    private void validateNodeId(String nodeId) {
        if (nodeId == null || !nodeId.contains(":")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "invalid graph node id");
        }
    }

    private Map<String, Object> buildAttributes(KnowledgeGraphNodeResponse node) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("type", node.getType().name());
        attributes.put("status", node.getStatus());
        attributes.put("indexStatus", node.getIndexStatus());
        attributes.put("subtitle", node.getSubtitle());
        attributes.put("spaceId", node.getSpaceId());
        attributes.put("refId", node.getRefId());
        return attributes;
    }

    private void maybeAddDocumentEdge(
            List<KnowledgeGraphEdgeResponse> edges,
            String sourceNodeId,
            Citation citation,
            Map<Long, Document> documentById,
            KnowledgeGraphEdgeType edgeType,
            String label
    ) {
        if ("DOCUMENT".equalsIgnoreCase(citation.getSourceType()) && documentById.containsKey(citation.getSourceId())) {
            edges.add(edge(sourceNodeId, nodeId(KnowledgeGraphNodeType.DOCUMENT, citation.getSourceId()), edgeType, label, 1));
        }
    }

    private KnowledgeGraphNodeResponse node(
            KnowledgeGraphNodeType type,
            Long refId,
            Long spaceId,
            String title,
            String subtitle,
            String status,
            String indexStatus,
            boolean root
    ) {
        return KnowledgeGraphNodeResponse.builder()
                .id(nodeId(type, refId))
                .refId(refId)
                .spaceId(spaceId)
                .type(type)
                .title(title)
                .subtitle(subtitle)
                .status(status)
                .indexStatus(indexStatus)
                .root(root)
                .build();
    }

    private List<Document> loadDocumentsForSpace(Long spaceId) {
        List<Document> documents = new ArrayList<>();
        for (Document document : documentRepository.findAll()) {
            if (Objects.equals(document.getSpaceId(), spaceId)
                    && document.getDeletedAt() == null
                    && document.getStatus() != DocumentStatus.DELETED) {
                documents.add(document);
            }
        }
        documents.sort(Comparator.comparing(Document::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return documents;
    }

    private List<ChatMessage> loadChatMessagesForSpace(List<WikiPage> wikiPages, List<Artifact> artifacts) {
        Set<Long> messageIds = new LinkedHashSet<>();
        for (WikiPage page : wikiPages) {
            if (page.getSourceMessageId() != null) {
                messageIds.add(page.getSourceMessageId());
            }
        }
        for (Artifact artifact : artifacts) {
            if (artifact.getCreatedFromMessageId() != null) {
                messageIds.add(artifact.getCreatedFromMessageId());
            }
            for (ArtifactSource source : artifactSourceRepository.findByArtifactIdOrderByIdAsc(artifact.getId())) {
                if (source.getSourceType() == ArtifactSourceType.CHAT_MESSAGE) {
                    messageIds.add(source.getSourceId());
                }
            }
        }

        List<ChatMessage> messages = new ArrayList<>(chatMessageRepository.findAllById(messageIds));
        messages.sort(Comparator.comparing(ChatMessage::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return messages;
    }

    private KnowledgeGraphEdgeResponse mapArtifactSourceEdge(
            Long artifactId,
            ArtifactSource source,
            Map<Long, Document> documentById,
            Map<Long, ChatMessage> messageById,
            Map<Long, Artifact> artifactById
    ) {
        if (source.getSourceType() == ArtifactSourceType.DOCUMENT && documentById.containsKey(source.getSourceId())) {
            return edge(nodeId(KnowledgeGraphNodeType.ARTIFACT, artifactId),
                    nodeId(KnowledgeGraphNodeType.DOCUMENT, source.getSourceId()),
                    KnowledgeGraphEdgeType.ARTIFACT_SOURCE_DOCUMENT, "artifact-source-document", 1);
        }
        if (source.getSourceType() == ArtifactSourceType.CHAT_MESSAGE && messageById.containsKey(source.getSourceId())) {
            return edge(nodeId(KnowledgeGraphNodeType.ARTIFACT, artifactId),
                    nodeId(KnowledgeGraphNodeType.CHAT_MESSAGE, source.getSourceId()),
                    KnowledgeGraphEdgeType.ARTIFACT_SOURCE_MESSAGE, "artifact-source-message", 1);
        }
        if (source.getSourceType() == ArtifactSourceType.ARTIFACT && artifactById.containsKey(source.getSourceId())) {
            return edge(nodeId(KnowledgeGraphNodeType.ARTIFACT, artifactId),
                    nodeId(KnowledgeGraphNodeType.ARTIFACT, source.getSourceId()),
                    KnowledgeGraphEdgeType.ARTIFACT_SOURCE_ARTIFACT, "artifact-source-artifact", 1);
        }
        return null;
    }

    private KnowledgeGraphEdgeResponse edge(String sourceId, String targetId, KnowledgeGraphEdgeType type, String label, Integer weight) {
        return KnowledgeGraphEdgeResponse.builder()
                .sourceId(sourceId)
                .targetId(targetId)
                .type(type)
                .label(label)
                .weight(weight)
                .build();
    }

    private List<KnowledgeGraphEdgeResponse> dedupeEdges(List<KnowledgeGraphEdgeResponse> edges) {
        Map<String, KnowledgeGraphEdgeResponse> unique = new LinkedHashMap<>();
        for (KnowledgeGraphEdgeResponse edge : edges) {
            String key = edge.getSourceId() + "->" + edge.getTargetId() + ":" + edge.getType();
            unique.putIfAbsent(key, edge);
        }
        return new ArrayList<>(unique.values());
    }

    private <T> Map<Long, T> indexById(List<T> items) {
        Map<Long, T> result = new LinkedHashMap<>();
        for (T item : items) {
            Long id = extractId(item);
            if (id != null) {
                result.put(id, item);
            }
        }
        return result;
    }

    private Long extractId(Object value) {
        if (value instanceof WikiPage wikiPage) {
            return wikiPage.getId();
        }
        if (value instanceof Artifact artifact) {
            return artifact.getId();
        }
        if (value instanceof Document document) {
            return document.getId();
        }
        if (value instanceof ChatMessage message) {
            return message.getId();
        }
        if (value instanceof ArticleCard articleCard) {
            return articleCard.getId();
        }
        if (value instanceof ConceptCard conceptCard) {
            return conceptCard.getId();
        }
        if (value instanceof SynthesisCard synthesisCard) {
            return synthesisCard.getId();
        }
        if (value instanceof MethodologyCard methodologyCard) {
            return methodologyCard.getId();
        }
        return null;
    }

    private String buildMessageTitle(ChatMessage message) {
        String content = message.getContent() == null ? "" : message.getContent().trim();
        if (content.length() > 48) {
            content = content.substring(0, 48) + "...";
        }
        return content.isEmpty() ? "Chat Message #" + message.getId() : content;
    }

    private String nodeId(KnowledgeGraphNodeType type, Long refId) {
        return type.name() + ":" + refId;
    }

    private record NodeDepth(String nodeId, int depth) {
    }
}
