package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.answer.strategy.AnswerContext;
import com.noteweave.answer.strategy.AnswerMode;
import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.RetrievalPlan;
import com.noteweave.chat.NoteRetrievalService.CandidateSource;
import com.noteweave.chat.NoteRetrievalService.NoteRecallPlan;
import com.noteweave.chat.NoteRetrievalService.NoteRecallTrace;
import com.noteweave.chat.NoteRetrievalService.ReadingWindow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NoteEvidenceRetrieverTest {

    @Test
    void shouldAdaptLegacyWindowsAndSnapshotWithoutChangingTheirOrder() {
        NoteRecallRetriever recallRetriever = mock(NoteRecallRetriever.class);
        NoteRetrievalService retrievalService = mock(NoteRetrievalService.class);
        NoteReadingRetriever readingRetriever = mock(NoteReadingRetriever.class);
        NoteRetrievalSnapshotCodec codec =
                new NoteRetrievalSnapshotCodec(new ObjectMapper().findAndRegisterModules());
        CandidateSource candidate = candidate();
        NoteRecallPlan plan = new NoteRecallPlan(
                List.of(), List.of(candidate), List.of(), List.of(candidate),
                new NoteRecallTrace(0, 1, 0, 1, 1, 0, 0, 1));
        ReadingWindow first = window("chunk-a", 2, "anchor-window", "Report", "research_agent", "run-1");
        ReadingWindow second = window("chunk-b", 8, "continuation-window", "Source B", "", "");
        when(recallRetriever.retrieve("workspace", "query")).thenReturn(plan);
        when(retrievalService.readEntriesMetadataForNote("workspace", plan.verifySources(), "query"))
                .thenReturn(List.of());
        when(readingRetriever.retrieveWithDiagnostics("workspace", plan.verifySources(), "query"))
                .thenReturn(readingResult(List.of(first, second)));
        NoteEvidenceRetriever retriever = new NoteEvidenceRetriever(
                recallRetriever, retrievalService, readingRetriever, codec);

        RetrievalPlan.Step step = new RetrievalPlan.Step(
                NoteEvidenceRetriever.CHANNEL, 8, 1, Map.of());
        RetrievalPlan retrievalPlan = new RetrievalPlan(
                "note-test-v1", AnswerMode.NOTE, List.of(step),
                new RetrievalPlan.Budget(8, 24_000, 0, 0));
        var result = retriever.retrieve(
                new AnswerContext("workspace", "conversation", "message", "query",
                        Set.of(), Map.of(), Instant.now()),
                retrievalPlan,
                step
        );

        assertThat(result.evidence()).extracting(item -> item.passageId())
                .containsExactly("chunk-a", "chunk-b");
        assertThat(result.evidence().get(0).title()).isEqualTo("Report · Research Report(run-1)");
        assertThat(result.evidence().get(0).metadata().get("raw_title")).isEqualTo("Report");
        NoteRetrievalSnapshot snapshot = codec.decode(
                result.metadata().get(NoteRetrievalSnapshotCodec.METADATA_KEY));
        assertThat(snapshot.windows()).extracting(ReadingWindow::chunkId)
                .containsExactly("chunk-a", "chunk-b");
    }

    @Test
    void shouldApplyPlanCandidateLimitToEvidenceAndSnapshot() {
        NoteRecallRetriever recallRetriever = mock(NoteRecallRetriever.class);
        NoteRetrievalService retrievalService = mock(NoteRetrievalService.class);
        NoteReadingRetriever readingRetriever = mock(NoteReadingRetriever.class);
        NoteRetrievalSnapshotCodec codec =
                new NoteRetrievalSnapshotCodec(new ObjectMapper().findAndRegisterModules());
        CandidateSource candidate = candidate();
        NoteRecallPlan plan = new NoteRecallPlan(
                List.of(), List.of(candidate), List.of(), List.of(candidate),
                new NoteRecallTrace(0, 1, 0, 1, 1, 0, 0, 1));
        when(recallRetriever.retrieve("workspace", "query")).thenReturn(plan);
        when(retrievalService.readEntriesMetadataForNote("workspace", plan.verifySources(), "query"))
                .thenReturn(List.of());
        when(readingRetriever.retrieveWithDiagnostics("workspace", plan.verifySources(), "query"))
                .thenReturn(readingResult(List.of(
                        window("chunk-a", 2, "anchor-window", "A", "", ""),
                        window("chunk-b", 1, "continuation-window", "B", "", ""))));
        NoteEvidenceRetriever retriever = new NoteEvidenceRetriever(
                recallRetriever, retrievalService, readingRetriever, codec);

        RetrievalPlan.Step step = new RetrievalPlan.Step(
                NoteEvidenceRetriever.CHANNEL, 1, 1, Map.of());
        RetrievalPlan retrievalPlan = new RetrievalPlan(
                "note-test-v1", AnswerMode.NOTE, List.of(step),
                new RetrievalPlan.Budget(1, 24_000, 0, 0));
        var result = retriever.retrieve(
                new AnswerContext("workspace", "conversation", "message", "query",
                        Set.of(), Map.of(), Instant.now()),
                retrievalPlan,
                step
        );

        assertThat(result.evidence()).extracting(EvidenceBundle.Evidence::passageId)
                .containsExactly("chunk-a");
        NoteRetrievalSnapshot snapshot = codec.decode(
                result.metadata().get(NoteRetrievalSnapshotCodec.METADATA_KEY));
        assertThat(snapshot.windows()).extracting(ReadingWindow::chunkId)
                .containsExactly("chunk-a");
    }

    @Test
    void shouldPropagateReadingDegradationIntoEvidenceResult() {
        NoteRecallRetriever recallRetriever = mock(NoteRecallRetriever.class);
        NoteRetrievalService retrievalService = mock(NoteRetrievalService.class);
        NoteReadingRetriever readingRetriever = mock(NoteReadingRetriever.class);
        NoteRetrievalSnapshotCodec codec =
                new NoteRetrievalSnapshotCodec(new ObjectMapper().findAndRegisterModules());
        CandidateSource candidate = candidate();
        NoteRecallPlan plan = new NoteRecallPlan(
                List.of(), List.of(candidate), List.of(), List.of(candidate),
                new NoteRecallTrace(0, 1, 0, 1, 1, 0, 0, 1));
        when(recallRetriever.retrieve("workspace", "query")).thenReturn(plan);
        when(retrievalService.readEntriesMetadataForNote("workspace", plan.verifySources(), "query"))
                .thenReturn(List.of());
        when(readingRetriever.retrieveWithDiagnostics("workspace", plan.verifySources(), "query"))
                .thenReturn(new NoteReadingRetriever.ReadingResult(
                        List.of(window("chunk-a", 2, "anchor-window", "A", "", "")),
                        true,
                        List.of("NOTE_WINDOW_RERANK_UNAVAILABLE"),
                        Map.of("note_window_rerank_count", 0L)
                ));
        NoteEvidenceRetriever retriever = new NoteEvidenceRetriever(
                recallRetriever, retrievalService, readingRetriever, codec);
        RetrievalPlan.Step step = new RetrievalPlan.Step(
                NoteEvidenceRetriever.CHANNEL, 1, 1, Map.of());

        var result = retriever.retrieve(
                new AnswerContext("workspace", "conversation", "message", "query",
                        Set.of(), Map.of(), Instant.now()),
                new RetrievalPlan("note-test-v1", AnswerMode.NOTE, List.of(step),
                        new RetrievalPlan.Budget(1, 24_000, 0, 0)),
                step
        );

        assertThat(result.degraded()).isTrue();
        assertThat(result.degradationReasons()).containsExactly("NOTE_WINDOW_RERANK_UNAVAILABLE");
        assertThat(result.measurements()).containsEntry("note_window_rerank_count", 0L);
    }

    private NoteReadingRetriever.ReadingResult readingResult(List<ReadingWindow> windows) {
        return new NoteReadingRetriever.ReadingResult(
                windows, false, List.of(), Map.of(
                "note_window_candidate_count", (long) windows.size(),
                "note_window_semantic_hit_count", 0L,
                "note_window_rerank_count", 0L));
    }

    private CandidateSource candidate() {
        return new CandidateSource(
                "source", "Source", "MARKDOWN", 2, 2, "", "", "summary",
                "[]", "{}", "sample", 8, "metadata", List.of(), List.of(),
                1, 1, "metadata-quota", "candidate:metadata-quota"
        );
    }

    private ReadingWindow window(
            String chunkId,
            int score,
            String role,
            String title,
            String generatedBy,
            String generatedRefId
    ) {
        return new ReadingWindow(
                chunkId, "source", "snapshot", 0, "Heading", title,
                generatedBy, generatedRefId, 0, "content", "chunk:0", score,
                role, 0, "verify"
        );
    }
}
