package com.noteweave.retrieval.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetrievalBenchmarkReplayTest {
    @Test
    void shouldReplayVersionedGoldSetDeterministicallyAcrossAllAnswerModes() throws Exception {
        Path goldSet = Path.of(getClass().getResource(
                "/retrieval/stage5-retrieval-gold-v1.json").toURI());
        RetrievalBenchmarkReplay replay = new RetrievalBenchmarkReplay();

        var first = replay.replay(goldSet);
        var second = replay.replay(goldSet);

        assertThat(second).isEqualTo(first);
        assertThat(first.schemaVersion()).isEqualTo("retrieval-gold-v1");
        assertThat(first.datasetVersion()).isEqualTo("stage5-fixture-20260714");
        assertThat(first.rankingVersion()).isEqualTo("deterministic-bm25-v1");
        assertThat(first.cases()).hasSize(4);
        assertThat(first.byMode()).containsOnlyKeys("QA", "NOTE", "WIKI");
        assertThat(first.overall().retrievalCaseCount()).isEqualTo(3);
        assertThat(first.overall().refusalCaseCount()).isEqualTo(1);
        assertThat(first.overall().macroRecallAtK()).isEqualTo(1.0d);
        assertThat(first.overall().macroMrr()).isEqualTo(1.0d);
        assertThat(first.overall().macroNdcgAtK()).isEqualTo(1.0d);
        assertThat(first.overall().macroCitationPrecision()).isEqualTo(1.0d);
        assertThat(first.overall().macroCitationCoverage()).isEqualTo(1.0d);
        assertThat(first.overall().refusalAccuracy()).isEqualTo(1.0d);
        assertThat(first.overall().scopeViolationCount()).isZero();

        var qa = first.cases().stream()
                .filter(result -> result.id().equals("qa-spring-configuration"))
                .findFirst().orElseThrow();
        assertThat(qa.rankedEvidence()).extracting(item -> item.evidenceId())
                .containsExactly("qa-spring-config", "qa-spring-env");
        var note = first.cases().stream()
                .filter(result -> result.id().equals("note-recall-refactor"))
                .findFirst().orElseThrow();
        assertThat(note.rankedEvidence()).extracting(item -> item.evidenceId())
                .containsExactly("note-recall");
        var refusal = first.cases().stream()
                .filter(result -> result.id().equals("qa-insufficient-evidence"))
                .findFirst().orElseThrow();
        assertThat(refusal.rankedEvidence()).isEmpty();
        assertThat(refusal.refusalCorrect()).isTrue();
    }

    @Test
    void shouldRejectRelevantEvidenceOutsideAllowedScope() {
        RetrievalGoldSet.Candidate candidate = new RetrievalGoldSet.Candidate(
                "evidence", "forbidden-source", "Title", "content", "PDF", List.of("citation"));
        RetrievalGoldSet.GoldCase goldCase = new RetrievalGoldSet.GoldCase(
                "scope-case", "QA", "workspace", "content", List.of("allowed-source"), List.of(candidate),
                List.of("evidence"), List.of("citation"), false, 1);
        RetrievalGoldSet goldSet = new RetrievalGoldSet(
                "retrieval-gold-v1", "invalid-scope", List.of(goldCase));

        assertThatThrownBy(() -> new RetrievalBenchmarkReplay().replay(goldSet))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside allowed scope");
    }
}
