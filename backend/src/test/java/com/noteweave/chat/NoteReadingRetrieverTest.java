package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.noteweave.chat.NoteRetrievalService.CandidateSource;
import com.noteweave.chat.NoteRetrievalService.ReadingWindow;
import com.noteweave.retrieval.provider.EmbeddingClient;
import com.noteweave.retrieval.provider.RerankClient;
import com.noteweave.retrieval.qa.QaHybridSearchPort;
import com.noteweave.retrieval.qa.QaHybridSearchPort.QaSearchHit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NoteReadingRetrieverTest {

    @Test
    void emptyProviderResultShouldBeExplicitlyDegraded() {
        RetrievalHydrator hydrator = mock(RetrievalHydrator.class);
        RerankClient rerankClient = mock(RerankClient.class);
        when(rerankClient.isEnabled()).thenReturn(true);
        when(rerankClient.rerank(
                org.mockito.ArgumentMatchers.eq("query"),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.eq(1)))
                .thenReturn(new RerankClient.RerankResult(List.of(), "rerank-v1"));
        when(hydrator.loadWindowIndex(org.mockito.ArgumentMatchers.eq("workspace"),
                        org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RetrievalHydrator.WindowIndexResult(
                        List.of(identity("chunk-a", 1, 1)), 0, 0, 1));
        when(hydrator.hydrateWindowBodies(org.mockito.ArgumentMatchers.eq("workspace"),
                        org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RetrievalHydrator.BodyLoadResult(Map.of("chunk-a", "Content")));

        NoteReadingRetriever retriever = new NoteReadingRetriever(
                hydrator, new NoteReadingPlanner(), null, null, rerankClient);

        NoteReadingRetriever.ReadingResult result = retriever.retrieveWithDiagnostics(
                "workspace", List.of(source("source-a")), "query");

        assertThat(result.degraded()).isTrue();
        assertThat(result.degradationReasons()).contains("NOTE_WINDOW_RERANK_UNAVAILABLE");
        assertThat(result.windows()).extracting(ReadingWindow::chunkId).containsExactly("chunk-a");
    }

    @Test
    void invalidProviderIndexShouldBeExplicitlyDegraded() {
        RetrievalHydrator hydrator = mock(RetrievalHydrator.class);
        RerankClient rerankClient = mock(RerankClient.class);
        when(rerankClient.isEnabled()).thenReturn(true);
        when(rerankClient.rerank(
                org.mockito.ArgumentMatchers.eq("query"),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.eq(1)))
                .thenReturn(new RerankClient.RerankResult(
                        List.of(new RerankClient.Hit(99, 0.9, 1)), "rerank-v1"));
        when(hydrator.loadWindowIndex(org.mockito.ArgumentMatchers.eq("workspace"),
                        org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RetrievalHydrator.WindowIndexResult(
                        List.of(identity("chunk-a", 1, 1)), 0, 0, 1));
        when(hydrator.hydrateWindowBodies(org.mockito.ArgumentMatchers.eq("workspace"),
                        org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RetrievalHydrator.BodyLoadResult(Map.of("chunk-a", "Content")));

        NoteReadingRetriever retriever = new NoteReadingRetriever(
                hydrator, new NoteReadingPlanner(), null, null, rerankClient);

        NoteReadingRetriever.ReadingResult result = retriever.retrieveWithDiagnostics(
                "workspace", List.of(source("source-a")), "query");

        assertThat(result.degraded()).isTrue();
        assertThat(result.degradationReasons()).contains("NOTE_WINDOW_RERANK_UNAVAILABLE");
        assertThat(result.windows()).extracting(ReadingWindow::chunkId).containsExactly("chunk-a");
    }

    @Test
    void vectorRankCanOutweighLexicalRankInsideOneSource() {
        RetrievalHydrator hydrator = mock(RetrievalHydrator.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        QaHybridSearchPort chunkSearch = mock(QaHybridSearchPort.class);
        when(embeddingClient.isEnabled()).thenReturn(true);
        when(embeddingClient.embedQuery("target")).thenReturn(
                new EmbeddingClient.EmbeddingResult(List.of(List.of(1f)), "embedding", 1, 1));
        when(chunkSearch.vectorRetrieve(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new QaSearchHit(
                        "chunk-vector", "source-a", "snapshot-a", 2, "Source", "h", "PDF", "other", 0.9d)));
        when(hydrator.loadWindowIndex(org.mockito.ArgumentMatchers.eq("workspace"),
                        org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RetrievalHydrator.WindowIndexResult(
                        List.of(identity("chunk-lexical", 0, 1), identity("chunk-vector", 2, 0)),
                        1, 0, 1));
        when(hydrator.hydrateWindowBodies(org.mockito.ArgumentMatchers.eq("workspace"),
                        org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RetrievalHydrator.BodyLoadResult(
                        Map.of("chunk-lexical", "target", "chunk-vector", "other")));
        NoteReadingRetriever retriever = new NoteReadingRetriever(
                hydrator, new NoteReadingPlanner(), embeddingClient, chunkSearch, null);

        NoteReadingRetriever.ReadingResult result = retriever.retrieveWithDiagnostics(
                "workspace", List.of(source("source-a")), "target");

        assertThat(result.windows()).isNotEmpty();
        assertThat(result.windows().get(0).chunkId()).isEqualTo("chunk-vector");
        assertThat(result.windows().get(0).readRole()).isEqualTo("primary-window");
    }

    @Test
    void actualWindowRerankKeepsFallbackOriginRegardlessOfSourceOrigin() {
        RetrievalHydrator hydrator = mock(RetrievalHydrator.class);
        RerankClient rerank = mock(RerankClient.class);
        var info = com.noteweave.answer.strategy.ScoreInfo.llmFallback("llm-listwise-v1", 0.01);
        when(rerank.isEnabled()).thenReturn(true);
        when(rerank.rerank(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new RerankClient.RerankResult(List.of(new RerankClient.Hit(0, 0.01, 1, info)), "not-a-source-label"));
        when(hydrator.loadWindowIndex(org.mockito.ArgumentMatchers.eq("workspace"), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RetrievalHydrator.WindowIndexResult(List.of(identity("chunk-a", 1, 1)), 0, 0, 1));
        when(hydrator.hydrateWindowBodies(org.mockito.ArgumentMatchers.eq("workspace"),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RetrievalHydrator.BodyLoadResult(Map.of("chunk-a", "query")));
        var candidate = source("source-a").withSourceScoreInfo(com.noteweave.answer.strategy.ScoreInfo.provider("source-model", 0.9));
        var retriever = new NoteReadingRetriever(hydrator, new NoteReadingPlanner(), null, null, rerank);
        var result = retriever.retrieveWithDiagnostics("workspace", List.of(candidate), "query");
        assertThat(result.windows()).hasSize(1);
        assertThat(result.windows().get(0).scoreInfo()).isEqualTo(info);
        assertThat(candidate.sourceScoreInfo().origin()).isEqualTo("PROVIDER_MODEL");
        when(rerank.isEnabled()).thenReturn(false);
        var failed = retriever.retrieveWithDiagnostics("workspace", List.of(candidate), "query");
        assertThat(failed.windows().get(0).scoreInfo().origin()).isEqualTo("FUSION_FALLBACK");
        assertThat(failed.windows().get(0).scoreInfo().value()).isNull();
    }

    @Test
    void emptyBodyWindowsDoNotTakeReadingRolesOrTopN() {
        RetrievalHydrator hydrator = mock(RetrievalHydrator.class);
        when(hydrator.loadWindowIndex(org.mockito.ArgumentMatchers.eq("workspace"),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RetrievalHydrator.WindowIndexResult(
                        List.of(identity("chunk-empty", 0, 1), identity("chunk-full", 1, 1)), 0, 0, 2));
        when(hydrator.hydrateWindowBodies(org.mockito.ArgumentMatchers.eq("workspace"),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RetrievalHydrator.BodyLoadResult(Map.of("chunk-full", "Content")));
        NoteReadingRetriever retriever = new NoteReadingRetriever(
                hydrator, new NoteReadingPlanner(), null, null, null);

        NoteReadingRetriever.ReadingResult result = retriever.retrieveWithDiagnostics(
                "workspace", List.of(source("source-a")), "query");

        assertThat(result.windows()).extracting(ReadingWindow::chunkId).containsExactly("chunk-full");
        assertThat(result.windows()).extracting(ReadingWindow::readRole).containsExactly("primary-window");
        assertThat(result.measurements()).containsEntry("note_window_dropped_empty_body", 1L);
    }

    @Test
    void changedRecalledSnapshotDropsTheSourceInsteadOfMixingWindows() {
        RetrievalHydrator hydrator = mock(RetrievalHydrator.class);
        when(hydrator.loadWindowIndex(org.mockito.ArgumentMatchers.eq("workspace"),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RetrievalHydrator.WindowIndexResult(List.of(), 0, 0, 0, 1));
        when(hydrator.hydrateWindowBodies(org.mockito.ArgumentMatchers.eq("workspace"),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RetrievalHydrator.BodyLoadResult(Map.of()));
        NoteReadingRetriever retriever = new NoteReadingRetriever(
                hydrator, new NoteReadingPlanner(), null, null, null);

        NoteReadingRetriever.ReadingResult result = retriever.retrieveWithDiagnostics(
                "workspace", List.of(source("source-a", "snapshot-old")), "query");

        assertThat(result.windows()).isEmpty();
        assertThat(result.degradationReasons()).contains("NOTE_SOURCE_SNAPSHOT_CHANGED");
        assertThat(result.measurements()).containsEntry("note_window_snapshot_changed_sources", 1L);
    }

    @Test
    void semanticHitsFromAnotherSnapshotAreIgnored() {
        RetrievalHydrator hydrator = mock(RetrievalHydrator.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        QaHybridSearchPort chunkSearch = mock(QaHybridSearchPort.class);
        when(embeddingClient.isEnabled()).thenReturn(true);
        when(embeddingClient.embedQuery("target")).thenReturn(
                new EmbeddingClient.EmbeddingResult(List.of(List.of(1f)), "embedding", 1, 1));
        when(chunkSearch.vectorRetrieve(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                new QaSearchHit("chunk-old", "source-a", "snapshot-old", 2, "Source", "h", "PDF", "other", 0.9d)));
        when(hydrator.loadWindowIndex(org.mockito.ArgumentMatchers.eq("workspace"),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RetrievalHydrator.WindowIndexResult(
                        List.of(identity("chunk-old", 2, 0)), 1, 0, 0));
        when(hydrator.hydrateWindowBodies(org.mockito.ArgumentMatchers.eq("workspace"),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RetrievalHydrator.BodyLoadResult(Map.of("chunk-old", "other")));
        NoteReadingRetriever retriever = new NoteReadingRetriever(
                hydrator, new NoteReadingPlanner(), embeddingClient, chunkSearch, null);

        retriever.retrieveWithDiagnostics(
                "workspace", List.of(source("source-a", "snapshot-new")), "target");

        var chunkIds = org.mockito.ArgumentCaptor.forClass(List.class);
        var scores = org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(hydrator).loadWindowIndex(
                org.mockito.ArgumentMatchers.eq("workspace"), org.mockito.ArgumentMatchers.anyList(),
                chunkIds.capture(), scores.capture(), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any());
        // 旧快照的语义命中不能进入本次候选快照
        assertThat(chunkIds.getValue()).isEmpty();
        assertThat(scores.getValue()).isEmpty();
    }

    private CandidateSource source(String id) {
        return source(id, "");
    }

    private CandidateSource source(String id, String snapshotId) {
        return new CandidateSource(id, "Source", "PDF", 1, 1, "", "", "summary",
                "[]", "{}", "sample", 1, "", List.of(), List.of(), 0, 0, "", "", snapshotId);
    }

    private RetrievalHydrator.WindowIdentity identity(String chunkId, int chunkNo, int windowNo) {
        return new RetrievalHydrator.WindowIdentity(chunkId, "source-a", "snapshot-a", chunkId, chunkNo,
                "Heading", "Source", "", "", windowNo, "location", "head-fallback");
    }

    private ReadingWindow window(String chunkId) {
        return window(chunkId, 1, 1, "Content");
    }

    private ReadingWindow window(String chunkId, int chunkNo, int windowNo, String content) {
        return new ReadingWindow(chunkId, "source-a", "snapshot-a", chunkNo, "Heading", "Source",
                "", "", windowNo, content, "location", 1, "candidate-window", windowNo, "candidate-pool");
    }
}
