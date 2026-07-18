package com.noteweave.retrieval.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.search.ChunkSearchHit;
import com.noteweave.search.ChunkSearchPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QaChunkSearchShadowCaptureTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldCaptureRealPortRankingAndCompareItAgainstSanitizedQaGold() throws Exception {
        Path goldSetPath = Path.of(getClass().getResource(
                "/retrieval/stage5-retrieval-gold-v1.json").toURI());
        ChunkSearchPort searchPort = mock(ChunkSearchPort.class);
        when(searchPort.search(
                "workspace-fixture", "spring boot configuration properties", 12))
                .thenReturn(List.of(
                        hit("qa-out-of-scope", "source-secret", 20.0d,
                                "Spring Boot configuration secret copy"),
                        hit("qa-react-state", "source-react", 19.0d,
                                "Browser component reducers update interface state"),
                        hit("qa-spring-config", "source-spring-guide", 18.0d,
                                "Spring Boot configuration properties bind external settings")
                ));
        when(searchPort.search("workspace-fixture", "quantum banana theorem", 12))
                .thenReturn(List.of(
                        hit("refusal-spring", "source-spring-guide", 8.0d,
                                "Typed application settings from environment variables"),
                        hit("refusal-react", "source-react", 7.0d,
                                "Browser component reducers update interface state")
                ));
        String salt = "0123456789abcdef-capture-salt";
        QaChunkSearchShadowCapture capture = new QaChunkSearchShadowCapture(
                searchPort,
                new RetrievalSnapshotSanitizer(),
                new ObjectMapper().findAndRegisterModules());

        Path sanitizedGoldOutput = tempDir.resolve("sanitized-gold.json");
        Path shadowOutput = tempDir.resolve("shadow.json");
        var result = capture.captureAndWrite(
                goldSetPath,
                sanitizedGoldOutput,
                shadowOutput,
                "es-port-capture-20260714",
                salt
        );

        assertThat(result.sanitizedGoldSet().cases()).hasSize(2)
                .allMatch(goldCase -> goldCase.mode().equals("QA"));
        assertThat(result.sanitizedGoldSet().datasetVersion())
                .isEqualTo("stage5-fixture-20260714-qa-shadow-sanitized");
        assertThat(result.shadowSnapshot().cases()).hasSize(2);
        assertThat(result.shadowSnapshot().cases()).extracting(item -> item.caseId())
                .containsExactlyElementsOf(result.sanitizedGoldSet().cases().stream()
                        .map(RetrievalGoldSet.GoldCase::id)
                        .toList());
        assertThat(result.shadowSnapshot().cases().get(0).rankedEvidence())
                .hasSize(1)
                .extracting(item -> item.evidenceId())
                .allMatch(id -> id.startsWith("evidence-"))
                .noneMatch(id -> id.contains("qa-"));
        assertThat(result.shadowSnapshot().cases().get(0).candidateCount()).isEqualTo(3);
        assertThat(result.shadowSnapshot().cases().get(1).candidateCount()).isEqualTo(2);
        assertThat(result.shadowSnapshot().cases().get(1).rankedEvidence()).isEmpty();
        assertThat(result.redactionStats().totalCount()).isZero();
        assertThat(Files.readString(sanitizedGoldOutput))
                .doesNotContain("workspace-fixture", "source-spring-guide", "qa-spring-config");
        assertThat(Files.readString(shadowOutput))
                .doesNotContain("source-secret", "qa-out-of-scope");

        var comparison = new RetrievalShadowComparator().compare(
                result.sanitizedGoldSet(), result.shadowSnapshot());
        assertThat(comparison.shadow().overall().macroRecallAtK()).isEqualTo(0.5d);
        assertThat(comparison.shadow().overall().refusalAccuracy()).isEqualTo(1.0d);
        assertThat(comparison.shadow().overall().scopeViolationCount()).isZero();
        verify(searchPort).search(
                "workspace-fixture", "spring boot configuration properties", 12);
        verify(searchPort).search("workspace-fixture", "quantum banana theorem", 12);
    }

    @Test
    void shouldCountUnknownAdmittedHitAsUnexpectedCitation() {
        RetrievalGoldSet goldSet = new RetrievalGoldSet(
                RetrievalBenchmarkReplay.GOLD_SCHEMA_VERSION,
                "unknown-hit",
                List.of(new RetrievalGoldSet.GoldCase(
                        "case", "QA", "workspace", "alpha beta", List.of("source"),
                        List.of(new RetrievalGoldSet.Candidate(
                                "known", "source", "Known", "alpha beta", "PDF",
                                List.of("citation-known"))),
                        List.of("known"),
                        List.of("citation-known"),
                        false,
                        2)));
        ChunkSearchPort searchPort = mock(ChunkSearchPort.class);
        when(searchPort.search("workspace", "alpha beta", 12)).thenReturn(List.of(
                hit("unknown", "source", 20.0d, "alpha beta unrelated expansion"),
                hit("known", "source", 18.0d, "alpha beta expected evidence")
        ));
        QaChunkSearchShadowCapture capture = new QaChunkSearchShadowCapture(
                searchPort,
                new RetrievalSnapshotSanitizer(),
                new ObjectMapper().findAndRegisterModules());

        var result = capture.capture(
                goldSet, "snapshot", "0123456789abcdef-unknown-hit-salt");
        var comparison = new RetrievalShadowComparator().compare(
                result.sanitizedGoldSet(), result.shadowSnapshot());

        assertThat(result.shadowSnapshot().cases()).singleElement().satisfies(item -> {
            assertThat(item.rankedEvidence()).hasSize(2);
            assertThat(item.rankedEvidence().get(0).citationIds()).singleElement()
                    .asString().startsWith("citation-unmapped-");
        });
        assertThat(comparison.shadow().overall().macroCitationPrecision()).isEqualTo(0.5d);
        assertThat(comparison.shadow().overall().macroCitationCoverage()).isEqualTo(1.0d);
    }

    private ChunkSearchHit hit(String chunkId, String sourceId, double score, String content) {
        return new ChunkSearchHit(
                chunkId, sourceId, "snapshot", "0", chunkId, "PDF", content, score);
    }
}
