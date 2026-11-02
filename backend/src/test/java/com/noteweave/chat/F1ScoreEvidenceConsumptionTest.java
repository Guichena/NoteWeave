package com.noteweave.chat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.answer.EvidenceSufficiency;
import com.noteweave.answer.strategy.*;
import com.noteweave.knowledge.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class F1ScoreEvidenceConsumptionTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final AnswerContext context = new AnswerContext("w", "c", "m", "q", Set.of(), Map.of(), Instant.now());
    @Test
    void noteEvidenceConsumesWindowOriginIndependentlyOfSourceFailure() {
        var recall = mock(NoteRecallRetriever.class);
        var retrieval = mock(NoteRetrievalService.class);
        var reading = mock(NoteReadingRetriever.class);
        var source = new NoteRetrievalService.CandidateSource("s", "t", "PDF", 1, 1, "", "", "summary", "[]", "{}", "sample", 1, "", List.of(), List.of(), 0, 0, "", "")
                .withSourceScoreInfo(ScoreInfo.fusion());
        var plan = new NoteRetrievalService.NoteRecallPlan(List.of(), List.of(source), List.of(), List.of(source), null,
                true, List.of("NOTE_SOURCE_RERANK_UNAVAILABLE"), Map.of());
        when(recall.retrieve("w", "q")).thenReturn(plan);
        when(retrieval.readEntriesMetadataForNote(anyString(), anyList(), anyString())).thenReturn(List.of());
        var base = new NoteRetrievalService.ReadingWindow("chunk", "s", "snap", 0, "h", "t", "", "", 0, "body", "loc", 1, "primary", 0, "read");
        var codec = new NoteRetrievalSnapshotCodec(mapper);
        var retriever = new NoteEvidenceRetriever(recall, retrieval, reading, codec);
        var step = new RetrievalPlan.Step(NoteEvidenceRetriever.CHANNEL, 5, 7, Map.of());
        var rp = new RetrievalPlan("p", AnswerMode.NOTE, List.of(step), new RetrievalPlan.Budget(5, 20000, 0, 0));
        for (ScoreInfo info : List.of(ScoreInfo.llmFallback("llm-listwise-v1", 0.01),
                ScoreInfo.provider("actual-window", 0.349, ScoreInfo.UNIT_INTERVAL, ScoreInfo.KNOWN_PROVIDER_015_035))) {
            when(reading.retrieveWithDiagnostics("w", plan.verifySources(), "q"))
                    .thenReturn(new NoteReadingRetriever.ReadingResult(List.of(base.withRerankScore(info.value(), info)), false, List.of(), Map.of()));
            var result = retriever.retrieve(context, rp, step);
            assertThat(result.evidence().get(0).scoreInfo()).isEqualTo(info);
            assertThat(codec.decode(result.metadata().get(NoteRetrievalSnapshotCodec.METADATA_KEY)).recallPlan()
                    .verifySources().get(0).sourceScoreInfo().origin()).isEqualTo("FUSION_FALLBACK");
            assertThat(EvidenceSufficiencyJudge.combine(EvidenceSufficiency.SUFFICIENT, result.evidence()).verdict())
                    .isEqualTo(info.origin().equals("LLM_FALLBACK") ? EvidenceSufficiency.SUFFICIENT : EvidenceSufficiency.PARTIAL);
        }
    }
    @Test
    void wikiEvidenceNeverReconstructsProviderValueFromDisplayOrWeight() {
        var port = mock(WikiRetrievalQueryPort.class);
        var codec = new WikiRetrievalSnapshotCodec(mapper);
        when(port.citationIdsForWikiPages(anyString(), anyList())).thenReturn(List.of());
        when(port.consumeWikiDegradationReasons()).thenReturn(List.of("WIKI_INDEX_UNAVAILABLE"));
        var retriever = new WikiEvidenceRetriever(port, codec);
        var step = new RetrievalPlan.Step(WikiEvidenceRetriever.CHANNEL, 5, 9, Map.of());
        var plan = new RetrievalPlan("p", AnswerMode.WIKI, List.of(step), new RetrievalPlan.Budget(5, 20000, 0, 0));
        for (ScoreInfo info : List.of(ScoreInfo.provider("actual", 0.149, ScoreInfo.UNIT_INTERVAL, ScoreInfo.KNOWN_PROVIDER_015_035),
                ScoreInfo.llmFallback("llm-listwise-v1", 0.01), ScoreInfo.lexical("bm25", 12.3), ScoreInfo.fusion())) {
            var page = new KnowledgePageHit("i", "v", 1, "t", "body", "summary", 15, info);
            when(port.findRelevantWikiPageContexts("w", "q")).thenReturn(List.of(new WikiPageContext(page, List.of(), List.of(), List.of())));
            var result = retriever.retrieve(context, plan, step);
            assertThat(result.evidence().get(0).scoreInfo()).isEqualTo(info);
            assertThat(result.evidence().get(0).fusedScore()).isEqualTo(135);
            assertThat(EvidenceSufficiencyJudge.combine(EvidenceSufficiency.SUFFICIENT, result.evidence()).verdict())
                    .isEqualTo(info.eligibleForThreshold() ? EvidenceSufficiency.INSUFFICIENT : EvidenceSufficiency.SUFFICIENT);
        }
    }
}
