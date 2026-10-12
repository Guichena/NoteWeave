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
        when(hydrator.hydrateNoteWindows("workspace", List.of("source-a")))
                .thenReturn(Map.of("source-a", List.of(window("chunk-a"))));

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
        when(hydrator.hydrateNoteWindows("workspace", List.of("source-a")))
                .thenReturn(Map.of("source-a", List.of(window("chunk-a"))));

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
        when(hydrator.hydrateNoteWindows("workspace", List.of("source-a")))
                .thenReturn(Map.of("source-a", List.of(
                        window("chunk-lexical", 0, 1, "target"),
                        window("chunk-vector", 2, 0, "other"))));
        NoteReadingRetriever retriever = new NoteReadingRetriever(
                hydrator, new NoteReadingPlanner(), embeddingClient, chunkSearch, null);

        NoteReadingRetriever.ReadingResult result = retriever.retrieveWithDiagnostics(
                "workspace", List.of(source("source-a")), "target");

        assertThat(result.windows()).isNotEmpty();
        assertThat(result.windows().get(0).chunkId()).isEqualTo("chunk-vector");
        assertThat(result.windows().get(0).readRole()).isEqualTo("primary-window");
    }

    private CandidateSource source(String id) {
        return new CandidateSource(id, "Source", "PDF", 1, 1, "", "", "summary",
                "[]", "{}", "sample", 1, "", List.of(), List.of(), 0, 0, "", "");
    }

    private ReadingWindow window(String chunkId) {
        return window(chunkId, 1, 1, "Content");
    }

    private ReadingWindow window(String chunkId, int chunkNo, int windowNo, String content) {
        return new ReadingWindow(chunkId, "source-a", "snapshot-a", chunkNo, "Heading", "Source",
                "", "", windowNo, content, "location", 1, "candidate-window", windowNo, "candidate-pool");
    }
}
