package com.noteweave.retrieval.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.retrieval.QaEvidenceRelevancePolicy;
import com.noteweave.retrieval.eval.QaGoldAnnotationDraft.DraftCase;
import com.noteweave.search.ChunkSearchHit;
import com.noteweave.search.ChunkSearchPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QaGoldAnnotationCompilerTest {
    private static final String SALT = "0123456789abcdef-reviewed-salt";

    @TempDir
    Path tempDir;

    @Test
    void shouldCompileReviewedDraftDirectlyToSanitizedGoldAndShadow() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        Path requestPath = resource("stage5-qa-gold-annotation-request-v1.json");
        QaGoldAnnotationRequest request = objectMapper.readValue(
                requestPath.toFile(), QaGoldAnnotationRequest.class);
        ChunkSearchPort searchPort = mock(ChunkSearchPort.class);
        when(searchPort.search(
                "workspace-private",
                "spring secret token=abcdefgh1234 alice@example.com",
                5
        )).thenReturn(hits());
        QaGoldAnnotationDraftCapture capture = capture(searchPort, objectMapper);
        QaGoldAnnotationDraft pending = capture.capture(request, SALT).draft();
        QaGoldAnnotationDraft reviewed = reviewed(pending, 0);
        Path reviewedPath = tempDir.resolve("reviewed.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(reviewedPath.toFile(), reviewed);
        Path goldOutput = tempDir.resolve("gold.json");
        Path shadowOutput = tempDir.resolve("shadow.json");
        QaGoldAnnotationCompiler compiler = new QaGoldAnnotationCompiler(capture, objectMapper);

        var result = compiler.compileAndWrite(
                requestPath,
                reviewedPath,
                goldOutput,
                shadowOutput,
                "real-port-reviewed-20260714",
                SALT
        );

        assertThat(result.sanitizedGoldSet().datasetVersion())
                .isEqualTo("stage5-realistic-qa-draft-20260714-reviewed-sanitized");
        assertThat(result.sanitizedGoldSet().cases()).singleElement().satisfies(goldCase -> {
            assertThat(goldCase.relevantEvidenceIds()).hasSize(1);
            assertThat(goldCase.expectedCitationIds()).hasSize(1);
            assertThat(goldCase.shouldRefuse()).isFalse();
            assertThat(goldCase.candidates()).hasSize(3);
            assertThat(goldCase.id()).startsWith("case-");
            assertThat(goldCase.workspaceId()).startsWith("workspace-");
        });
        assertThat(result.shadowSnapshot().snapshotVersion())
                .isEqualTo("real-port-reviewed-20260714");
        assertThat(result.shadowSnapshot().cases()).singleElement().satisfies(item -> {
            assertThat(item.candidateCount()).isEqualTo(3);
            assertThat(item.rankedEvidence()).hasSize(1);
        });
        var comparison = new RetrievalShadowComparator().compare(
                result.sanitizedGoldSet(), result.shadowSnapshot());
        assertThat(comparison.shadow().overall().macroRecallAtK()).isEqualTo(1.0d);
        assertThat(comparison.shadow().overall().macroCitationCoverage()).isEqualTo(1.0d);
        assertThat(comparison.shadow().overall().scopeViolationCount()).isZero();
        String outputs = Files.readString(goldOutput) + Files.readString(shadowOutput);
        assertThat(outputs)
                .doesNotContain(
                        "workspace-private", "source-allowed", "source-denied",
                        "chunk-allowed", "chunk-out-of-scope", "abcdefgh1234",
                        "alice@example.com", "13800138000", "10.1.2.3", "D:\\private")
                .contains("evidence-", "source-", "citation-", "[REDACTED]", "[EMAIL]");
        verify(searchPort, times(2)).search(
                "workspace-private",
                "spring secret token=abcdefgh1234 alice@example.com",
                5);
    }

    @Test
    void shouldRejectCandidateDriftBetweenReviewAndCompilation() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        QaGoldAnnotationRequest request = request();
        ChunkSearchPort searchPort = mock(ChunkSearchPort.class);
        when(searchPort.search("workspace", "query", 2))
                .thenReturn(List.of(hit("chunk-a", "source-allowed", 10.0d)))
                .thenReturn(List.of(hit("chunk-b", "source-allowed", 11.0d)));
        QaGoldAnnotationDraftCapture capture = capture(searchPort, objectMapper);
        QaGoldAnnotationDraft reviewed = reviewed(capture.capture(request, SALT).draft(), 0);
        QaGoldAnnotationCompiler compiler = new QaGoldAnnotationCompiler(capture, objectMapper);

        assertThatThrownBy(() -> compiler.compile(request, reviewed, "snapshot", SALT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("candidate drift detected");
    }

    @Test
    void shouldRejectRawContentDriftEvenWhenRedactedDraftTextIsUnchanged() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        QaGoldAnnotationRequest request = request();
        ChunkSearchPort searchPort = mock(ChunkSearchPort.class);
        when(searchPort.search("workspace", "query", 2))
                .thenReturn(List.of(new ChunkSearchHit(
                        "chunk-a", "source-allowed", "snapshot", "0",
                        "Contact", "MARKDOWN", "alice@example.com", 10.0d)))
                .thenReturn(List.of(new ChunkSearchHit(
                        "chunk-a", "source-allowed", "snapshot", "0",
                        "Contact", "MARKDOWN", "bob@example.com", 10.0d)));
        QaGoldAnnotationDraftCapture capture = capture(searchPort, objectMapper);
        QaGoldAnnotationDraft reviewed = reviewed(capture.capture(request, SALT).draft(), 0);
        QaGoldAnnotationCompiler compiler = new QaGoldAnnotationCompiler(capture, objectMapper);

        assertThat(reviewed.cases().get(0).candidates().get(0).content()).isEqualTo("[EMAIL]");
        assertThatThrownBy(() -> compiler.compile(request, reviewed, "snapshot", SALT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("candidate drift detected");
    }

    @Test
    void shouldBuildCompilerShadowFromRawAdmissionBeforeRedaction() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        String query = "How does AlphaBetaGuide perform rollback verification using "
                + "https://private.example/rollback/verification?";
        QaGoldAnnotationRequest request = new QaGoldAnnotationRequest(
                QaGoldAnnotationDraftCapture.REQUEST_SCHEMA_VERSION,
                "raw-admission",
                2,
                List.of(new QaGoldAnnotationRequest.CaseRequest(
                        "case", "workspace", query, List.of("source-allowed"), 1)));
        ChunkSearchHit hit = new ChunkSearchHit(
                "chunk-a", "source-allowed", "snapshot", "0",
                "Guide", "MARKDOWN",
                "See https://private.example/rollback/verification for details.", 10.0d);
        ChunkSearchPort searchPort = mock(ChunkSearchPort.class);
        when(searchPort.search("workspace", query, 2)).thenReturn(List.of(hit));
        QaGoldAnnotationDraftCapture capture = capture(searchPort, objectMapper);
        QaGoldAnnotationDraft pending = capture.capture(request, SALT).draft();
        QaGoldAnnotationDraft reviewed = reviewed(pending, 0);

        assertThat(QaEvidenceRelevancePolicy.isRelevant(
                pending.cases().get(0).query(),
                pending.cases().get(0).candidates().get(0).title(),
                pending.cases().get(0).candidates().get(0).content())).isFalse();

        var result = new QaGoldAnnotationCompiler(capture, objectMapper)
                .compile(request, reviewed, "snapshot", SALT);

        assertThat(result.shadowSnapshot().cases()).singleElement().satisfies(item ->
                assertThat(item.rankedEvidence()).hasSize(1));
        assertThat(objectMapper.writeValueAsString(result))
                .doesNotContain("private.example", "/rollback/verification");
    }

    @Test
    void shouldRejectPendingOrOutOfScopeGroundTruth() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        QaGoldAnnotationRequest request = request();
        ChunkSearchPort searchPort = mock(ChunkSearchPort.class);
        when(searchPort.search("workspace", "query", 2)).thenReturn(List.of(
                hit("chunk-allowed", "source-allowed", 10.0d),
                hit("chunk-denied", "source-denied", 9.0d)
        ));
        QaGoldAnnotationDraftCapture capture = capture(searchPort, objectMapper);
        QaGoldAnnotationDraft pending = capture.capture(request, SALT).draft();
        QaGoldAnnotationCompiler compiler = new QaGoldAnnotationCompiler(capture, objectMapper);

        assertThatThrownBy(() -> compiler.compile(request, pending, "snapshot", SALT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be REVIEWED");

        QaGoldAnnotationDraft outOfScope = reviewed(pending, 1);
        assertThatThrownBy(() -> compiler.compile(request, outOfScope, "snapshot", SALT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside allowed scope");
    }

    private QaGoldAnnotationDraft reviewed(QaGoldAnnotationDraft pending, int relevantIndex) {
        DraftCase item = pending.cases().get(0);
        var relevant = item.candidates().get(relevantIndex);
        DraftCase reviewedCase = new DraftCase(
                item.id(),
                item.mode(),
                item.workspaceId(),
                item.query(),
                item.allowedSourceIds(),
                item.topK(),
                item.latencyMicros(),
                item.candidateCount(),
                item.rawInputFingerprint(),
                QaGoldAnnotationCompiler.REVIEWED_STATUS,
                false,
                List.of(relevant.evidenceId()),
                relevant.citationIds(),
                "reviewed by evaluator",
                item.candidates()
        );
        return new QaGoldAnnotationDraft(
                pending.schemaVersion(),
                pending.datasetVersion(),
                pending.candidatePoolSize(),
                pending.capturedAt(),
                List.of(reviewedCase)
        );
    }

    private QaGoldAnnotationRequest request() {
        return new QaGoldAnnotationRequest(
                QaGoldAnnotationDraftCapture.REQUEST_SCHEMA_VERSION,
                "dataset",
                2,
                List.of(new QaGoldAnnotationRequest.CaseRequest(
                        "case", "workspace", "query", List.of("source-allowed"), 2))
        );
    }

    private QaGoldAnnotationDraftCapture capture(
            ChunkSearchPort searchPort,
            ObjectMapper objectMapper
    ) {
        return new QaGoldAnnotationDraftCapture(
                searchPort,
                new RetrievalSnapshotSanitizer(objectMapper),
                objectMapper,
                Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    private List<ChunkSearchHit> hits() {
        return List.of(
                new ChunkSearchHit(
                        "chunk-allowed", "source-allowed", "snapshot-allowed", "7",
                        "Spring secret guide https://private.example/doc", "PDF",
                        "Spring secret token=abcdefgh1234. Call 13800138000 from 10.1.2.3 or open D:\\private\\note.txt",
                        12.5d),
                new ChunkSearchHit(
                        "chunk-irrelevant", "source-allowed", "snapshot-allowed", "9",
                        "Browser state", "MARKDOWN", "Reducers update interface state.", 11.0d),
                new ChunkSearchHit(
                        "chunk-out-of-scope", "source-denied", "snapshot-denied", "8",
                        "Forbidden", "MARKDOWN", "alice@example.com", 10.0d)
        );
    }

    private ChunkSearchHit hit(String chunkId, String sourceId, double score) {
        return new ChunkSearchHit(
                chunkId, sourceId, "snapshot", "0", chunkId, "MARKDOWN", "content", score);
    }

    private Path resource(String name) throws Exception {
        return Path.of(getClass().getResource("/retrieval/" + name).toURI());
    }
}
