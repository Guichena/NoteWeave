package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.answer.strategy.AnswerContext;
import com.noteweave.answer.strategy.AnswerMode;
import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.RetrievalPlan;
import com.noteweave.knowledge.KnowledgePageHit;
import com.noteweave.knowledge.WikiPageContext;
import com.noteweave.knowledge.WikiRetrievalQueryPort;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WikiEvidenceRetrieverTest {

    @Test
    void shouldAdaptKnowledgeVersionsAndCarryExistingCitationOrder() {
        WikiRetrievalQueryPort queryPort = mock(WikiRetrievalQueryPort.class);
        WikiRetrievalSnapshotCodec codec =
                new WikiRetrievalSnapshotCodec(new ObjectMapper().findAndRegisterModules());
        WikiPageContext page = new WikiPageContext(
                new KnowledgePageHit("item", "version", 3, "Page", "content", "summary", 9),
                List.of(), List.of(), List.of());
        when(queryPort.findRelevantWikiPageContexts("workspace", "query"))
                .thenReturn(List.of(page));
        when(queryPort.citationIdsForWikiPages("workspace", List.of(page.page())))
                .thenReturn(List.of("citation-a", "citation-b", "citation-a"));
        WikiEvidenceRetriever retriever = new WikiEvidenceRetriever(queryPort, codec);

        RetrievalPlan.Step step = new RetrievalPlan.Step(
                WikiEvidenceRetriever.CHANNEL, 5, 1, Map.of());
        RetrievalPlan plan = new RetrievalPlan(
                "wiki-test-v1", AnswerMode.WIKI, List.of(step),
                new RetrievalPlan.Budget(5, 20_000, 1, 30));
        var result = retriever.retrieve(
                new AnswerContext("workspace", "conversation", "message", "query",
                        Set.of(), Map.of(), Instant.now()),
                plan,
                step
        );

        assertThat(result.evidence()).extracting(EvidenceBundle.Evidence::evidenceId)
                .containsExactly("knowledge-version:version");
        assertThat(result.evidence().get(0).freshAt()).isNotNull();
        assertThat(result.evidence().get(0).metadata())
                .containsEntry("freshness_status", "CURRENT_AT_RETRIEVAL");
        assertThat(result.metadata().get(WikiEvidenceRetriever.EXISTING_CITATION_IDS_METADATA))
                .isEqualTo("citation-a,citation-b,citation-a");
        assertThat(result.metadata())
                .containsEntry(WikiEvidenceRetriever.GRAPH_HOPS_USED_METADATA, "0")
                .containsEntry(WikiEvidenceRetriever.GRAPH_NODES_USED_METADATA, "0")
                .containsEntry(WikiEvidenceRetriever.GRAPH_HOP_LIMIT_METADATA, "1")
                .containsEntry(WikiEvidenceRetriever.GRAPH_NODE_LIMIT_METADATA, "30")
                .containsEntry(WikiEvidenceRetriever.GRAPH_EDGES_USED_METADATA, "0")
                .containsEntry(WikiEvidenceRetriever.GRAPH_CHARACTERS_USED_METADATA, "0")
                .containsEntry(WikiEvidenceRetriever.GRAPH_EDGE_LIMIT_METADATA, "60")
                .containsEntry(WikiEvidenceRetriever.GRAPH_CHARACTER_LIMIT_METADATA, "4000");
        assertThat(result.measurements())
                .containsEntry("graph_hops_used", 0L)
                .containsEntry("graph_nodes_used", 0L)
                .containsEntry("graph_hop_limit", 1L)
                .containsEntry("graph_node_limit", 30L)
                .containsEntry("graph_edges_used", 0L)
                .containsEntry("graph_characters_used", 0L)
                .containsEntry("graph_edge_limit", 60L)
                .containsEntry("graph_character_limit", 4_000L);
        WikiRetrievalSnapshot snapshot = codec.decode(
                result.metadata().get(WikiRetrievalSnapshotCodec.METADATA_KEY));
        assertThat(snapshot.citationIds()).containsExactly("citation-a", "citation-b", "citation-a");
    }

    @Test
    void shouldApplyPlanCandidateLimitBeforeBuildingSnapshotAndCitations() {
        WikiRetrievalQueryPort queryPort = mock(WikiRetrievalQueryPort.class);
        WikiRetrievalSnapshotCodec codec =
                new WikiRetrievalSnapshotCodec(new ObjectMapper().findAndRegisterModules());
        WikiPageContext first = page("item-a", "version-a", "A");
        WikiPageContext second = page("item-b", "version-b", "B");
        when(queryPort.findRelevantWikiPageContexts("workspace", "query"))
                .thenReturn(List.of(first, second));
        when(queryPort.citationIdsForWikiPages("workspace", List.of(first.page())))
                .thenReturn(List.of("citation-a"));
        WikiEvidenceRetriever retriever = new WikiEvidenceRetriever(queryPort, codec);

        RetrievalPlan.Step step = new RetrievalPlan.Step(
                WikiEvidenceRetriever.CHANNEL, 1, 1, Map.of());
        RetrievalPlan plan = new RetrievalPlan(
                "wiki-test-v1", AnswerMode.WIKI, List.of(step),
                new RetrievalPlan.Budget(1, 20_000, 1, 30));
        var result = retriever.retrieve(
                new AnswerContext("workspace", "conversation", "message", "query",
                        Set.of(), Map.of(), Instant.now()),
                plan,
                step
        );

        assertThat(result.evidence()).extracting(EvidenceBundle.Evidence::evidenceId)
                .containsExactly("knowledge-version:version-a");
        WikiRetrievalSnapshot snapshot = codec.decode(
                result.metadata().get(WikiRetrievalSnapshotCodec.METADATA_KEY));
        assertThat(snapshot.contexts()).extracting(context -> context.page().versionId())
                .containsExactly("version-a");
        assertThat(snapshot.citationIds()).containsExactly("citation-a");
    }

    @Test
    void shouldCarryPageRelevanceIntoBudgetRankingScores() {
        WikiRetrievalQueryPort queryPort = mock(WikiRetrievalQueryPort.class);
        WikiRetrievalSnapshotCodec codec =
                new WikiRetrievalSnapshotCodec(new ObjectMapper().findAndRegisterModules());
        WikiPageContext low = scoredPage("item-low", "version-low", "Low", 1);
        WikiPageContext high = scoredPage("item-high", "version-high", "High", 9);
        when(queryPort.findRelevantWikiPageContexts("workspace", "query"))
                .thenReturn(List.of(low, high));
        when(queryPort.citationIdsForWikiPages("workspace", List.of(low.page(), high.page())))
                .thenReturn(List.of());

        WikiEvidenceRetriever retriever = new WikiEvidenceRetriever(queryPort, codec);
        RetrievalPlan.Step step = new RetrievalPlan.Step(
                WikiEvidenceRetriever.CHANNEL, 5, 2, Map.of());
        RetrievalPlan plan = new RetrievalPlan(
                "wiki-test-v1", AnswerMode.WIKI, List.of(step),
                new RetrievalPlan.Budget(5, 20_000, 1, 30));

        var result = retriever.retrieve(
                new AnswerContext("workspace", "conversation", "message", "query",
                        Set.of(), Map.of(), Instant.now()), plan, step);

        assertThat(result.evidence()).extracting(EvidenceBundle.Evidence::fusedScore)
                .containsExactly(2.0, 18.0);
        assertThat(result.evidence()).extracting(EvidenceBundle.Evidence::rerankScore)
                .containsExactly(2.0, 18.0);
    }

    private WikiPageContext page(String itemId, String versionId, String title) {
        return scoredPage(itemId, versionId, title, 1);
    }

    private WikiPageContext scoredPage(String itemId, String versionId, String title, int score) {
        return new WikiPageContext(
                new KnowledgePageHit(itemId, versionId, 1, title, "content", "summary", score),
                List.of(), List.of(), List.of());
    }
}
