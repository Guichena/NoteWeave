package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.noteweave.chat.NoteRetrievalService.CandidateSource;
import com.noteweave.chat.NoteRetrievalService.ReadingWindow;
import com.noteweave.retrieval.provider.RerankClient;
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

    private CandidateSource source(String id) {
        return new CandidateSource(id, "Source", "PDF", 1, 1, "", "", "summary",
                "[]", "{}", "sample", 1, "", List.of(), List.of(), 0, 0, "", "");
    }

    private ReadingWindow window(String chunkId) {
        return new ReadingWindow(chunkId, "source-a", "snapshot-a", 1, "Heading", "Source",
                "", "", 1, "Content", "location", 1, "candidate-window", 1, "candidate-pool");
    }
}
