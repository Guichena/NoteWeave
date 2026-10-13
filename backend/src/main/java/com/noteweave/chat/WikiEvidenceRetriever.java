package com.noteweave.chat;

import com.noteweave.answer.strategy.AnswerContext;
import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.EvidenceRetrievalResult;
import com.noteweave.answer.strategy.EvidenceRetriever;
import com.noteweave.answer.strategy.RetrievalPlan;
import com.noteweave.knowledge.KnowledgePageHit;
import com.noteweave.knowledge.WikiLexicalBoost;
import com.noteweave.knowledge.WikiPageContext;
import com.noteweave.knowledge.WikiRetrievalQueryPort;
import com.noteweave.retrieval.QueryRewrite;
import com.noteweave.retrieval.RetrievalChannels;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class WikiEvidenceRetriever implements EvidenceRetriever {

    public static final String CHANNEL = "WIKI_PAGE_GRAPH";
    public static final String EXISTING_CITATION_IDS_METADATA = "existing_citation_ids";
    public static final String RETRIEVED_EVIDENCE_IDS_METADATA = "wiki_retrieved_evidence_ids";
    public static final String GRAPH_HOPS_USED_METADATA = "wiki_graph_hops_used";
    public static final String GRAPH_NODES_USED_METADATA = "wiki_graph_nodes_used";
    public static final String GRAPH_HOP_LIMIT_METADATA = "wiki_graph_hop_limit";
    public static final String GRAPH_NODE_LIMIT_METADATA = "wiki_graph_node_limit";
    public static final String GRAPH_EDGES_USED_METADATA = "wiki_graph_edges_used";
    public static final String GRAPH_CHARACTERS_USED_METADATA = "wiki_graph_characters_used";
    public static final String GRAPH_EDGE_LIMIT_METADATA = "wiki_graph_edge_limit";
    public static final String GRAPH_CHARACTER_LIMIT_METADATA = "wiki_graph_character_limit";

    private final WikiRetrievalQueryPort wikiRetrievalQueryPort;
    private final WikiRetrievalSnapshotCodec snapshotCodec;
    private final WikiGraphBudgeter graphBudgeter;

    @Autowired
    public WikiEvidenceRetriever(
            WikiRetrievalQueryPort wikiRetrievalQueryPort,
            WikiRetrievalSnapshotCodec snapshotCodec,
            WikiGraphBudgeter graphBudgeter
    ) {
        this.wikiRetrievalQueryPort = wikiRetrievalQueryPort;
        this.snapshotCodec = snapshotCodec;
        this.graphBudgeter = graphBudgeter;
    }

    WikiEvidenceRetriever(
            WikiRetrievalQueryPort wikiRetrievalQueryPort,
            WikiRetrievalSnapshotCodec snapshotCodec
    ) {
        this(wikiRetrievalQueryPort, snapshotCodec, new WikiGraphBudgeter());
    }

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public EvidenceRetrievalResult retrieve(
            AnswerContext context,
            RetrievalPlan plan,
            RetrievalPlan.Step step
    ) {
        RetrievalChannels channels = QueryRewrite.channelsFrom(step.filters(), context.query());
        WikiLexicalBoost boost = new WikiLexicalBoost(
                channels.mustTerms(), channels.titleTerms(), channels.bodyTerms());
        String query = channels.legacySingleQuery() ? context.query() : wikiQuery(channels);
        List<WikiPageContext> candidateContexts = (channels.legacySingleQuery() || boost.empty()
                ? wikiRetrievalQueryPort.findRelevantWikiPageContexts(context.workspaceId(), query)
                : wikiRetrievalQueryPort.findRelevantWikiPageContexts(context.workspaceId(), query, boost))
                .stream()
                .limit(Math.max(0, step.candidateLimit()))
                .toList();
        List<String> degradationReasons = wikiRetrievalQueryPort.consumeWikiDegradationReasons();
        if (degradationReasons == null) {
            degradationReasons = List.of();
        }
        WikiGraphBudgeter.Result graph = graphBudgeter.apply(candidateContexts, plan.budget());
        List<WikiPageContext> contexts = graph.contexts();
        List<String> citationIds = wikiRetrievalQueryPort.citationIdsForWikiPages(context.workspaceId(),
                contexts.stream().map(WikiPageContext::page).toList());
        WikiRetrievalSnapshot snapshot = new WikiRetrievalSnapshot(contexts, citationIds);
        List<EvidenceBundle.Evidence> evidence = java.util.stream.IntStream.range(0, contexts.size())
                .mapToObj(index -> toEvidence(contexts.get(index), index, contexts.size(), step.weight()))
                .toList();
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(WikiRetrievalSnapshotCodec.METADATA_KEY, snapshotCodec.encode(snapshot));
        metadata.put(EXISTING_CITATION_IDS_METADATA, String.join(",", citationIds));
        metadata.put(RETRIEVED_EVIDENCE_IDS_METADATA, evidence.stream()
                .map(EvidenceBundle.Evidence::evidenceId)
                .collect(java.util.stream.Collectors.joining(",")));
        metadata.put(GRAPH_HOPS_USED_METADATA, Integer.toString(graph.graphHopsUsed()));
        metadata.put(GRAPH_NODES_USED_METADATA, Integer.toString(graph.expandedNodeCount()));
        metadata.put(GRAPH_HOP_LIMIT_METADATA, Integer.toString(graph.graphHopLimit()));
        metadata.put(GRAPH_NODE_LIMIT_METADATA, Integer.toString(graph.expandedNodeLimit()));
        metadata.put(GRAPH_EDGES_USED_METADATA, Integer.toString(graph.graphEdgeCount()));
        metadata.put(GRAPH_CHARACTERS_USED_METADATA, Integer.toString(graph.graphCharacterCount()));
        metadata.put(GRAPH_EDGE_LIMIT_METADATA, Integer.toString(graph.graphEdgeLimit()));
        metadata.put(GRAPH_CHARACTER_LIMIT_METADATA, Integer.toString(graph.graphCharacterLimit()));
        return new EvidenceRetrievalResult(
                evidence,
                metadata,
                !degradationReasons.isEmpty(),
                degradationReasons,
                Map.of(
                        "graph_hops_used", (long) graph.graphHopsUsed(),
                        "graph_nodes_used", (long) graph.expandedNodeCount(),
                        "graph_hop_limit", (long) graph.graphHopLimit(),
                        "graph_node_limit", (long) graph.expandedNodeLimit(),
                        "graph_edges_used", (long) graph.graphEdgeCount(),
                        "graph_characters_used", (long) graph.graphCharacterCount(),
                        "graph_edge_limit", (long) graph.graphEdgeLimit(),
                        "graph_character_limit", (long) graph.graphCharacterLimit()
                )
        );
    }

    /**
     * Wiki 的覆盖判定按组取或。关键词查询是「当前问题 + 话题锚点」拼在一起时，
     * 追问里的虚词会把锚点稀释到阈值以下。这里拆回两行，解析器会分成两组。
     * must_terms 仍然只做加权，不进入覆盖判定。
     */
    private static String wikiQuery(RetrievalChannels channels) {
        String lexical = channels.lexicalQuery();
        String current = channels.vectorQuery();
        if (current.isBlank() || !lexical.startsWith(current)) {
            return lexical;
        }
        String anchor = lexical.substring(current.length()).strip();
        if (anchor.isBlank()) {
            return lexical;
        }
        return "当前问题：" + current + "\n主题锚点：" + anchor;
    }

    private EvidenceBundle.Evidence toEvidence(
            WikiPageContext context,
            int index,
            int total,
            double weight
    ) {
        KnowledgePageHit page = context.page();
        String excerpt = page.summary().isBlank() ? page.content() : page.summary();
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("version_no", Integer.toString(page.versionNo()));
        metadata.put("outgoing_link_count", Integer.toString(context.outgoingLinks().size()));
        metadata.put("backlink_count", Integer.toString(context.backlinks().size()));
        metadata.put("citation_count", Integer.toString(context.citations().size()));
        metadata.put("citation_ids", context.citations().stream()
                .map(citation -> citation.citationId()).collect(java.util.stream.Collectors.joining(",")));
        metadata.put("freshness_status", "CURRENT_AT_RETRIEVAL");
        // The shared evidence budgeter ranks by fusedScore. Preserve the query
        // adapter's relevance score instead of replacing it with list position.
        double orderingScore = page.score() * weight;
        return new EvidenceBundle.Evidence(
                "knowledge-version:" + page.versionId(),
                "KNOWLEDGE_VERSION",
                "",
                "",
                "",
                page.itemId(),
                page.versionId(),
                page.title(),
                excerpt,
                "knowledge-version:" + page.versionId(),
                page.score(),
                orderingScore,
                orderingScore,
                "workspace-knowledge:" + page.itemId(),
                java.time.Instant.now(),
                "wiki-page-graph",
                excerpt == null ? 0 : excerpt.length(),
                metadata
        );
    }
}
