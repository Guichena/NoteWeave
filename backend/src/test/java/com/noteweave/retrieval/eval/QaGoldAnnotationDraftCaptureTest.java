package com.noteweave.retrieval.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
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

class QaGoldAnnotationDraftCaptureTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldCaptureOnlySanitizedPendingAnnotationDraftFromRealPortBoundary() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        Path requestPath = Path.of(getClass().getResource(
                "/retrieval/stage5-qa-gold-annotation-request-v1.json").toURI());
        QaGoldAnnotationRequest request = objectMapper.readValue(
                requestPath.toFile(), QaGoldAnnotationRequest.class);
        ChunkSearchPort searchPort = mock(ChunkSearchPort.class);
        when(searchPort.search(
                "workspace-private",
                "spring secret token=abcdefgh1234 alice@example.com",
                5
        )).thenReturn(List.of(
                new ChunkSearchHit(
                        "chunk-allowed", "source-allowed",
                        "123e4567-e89b-12d3-a456-426614174000", "7",
                        "Guide https://private.example/doc", "PDF",
                        "Call 13800138000 from 10.1.2.3 or open D:\\private\\note.txt "
                                + "and D:/java-projects/NoteWeave-v2/docs/README.md",
                        12.5d),
                new ChunkSearchHit(
                        "chunk-out-of-scope", "source-denied", "snapshot-denied", "8",
                        "Forbidden", "MARKDOWN", "alice@example.com", 10.0d)
        ));
        QaGoldAnnotationDraftCapture capture = new QaGoldAnnotationDraftCapture(
                searchPort,
                new RetrievalSnapshotSanitizer(objectMapper),
                objectMapper,
                Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC));
        Path output = tempDir.resolve("qa-annotation-draft.json");

        var result = capture.captureAndWrite(
                requestPath, output, "0123456789abcdef-annotation-salt");

        assertThat(result.draft().schemaVersion())
                .isEqualTo(QaGoldAnnotationDraftCapture.DRAFT_SCHEMA_VERSION);
        assertThat(result.draft().datasetVersion())
                .isEqualTo("stage5-realistic-qa-draft-20260714-annotation-draft");
        assertThat(result.draft().capturedAt()).isEqualTo(Instant.parse("2026-07-14T00:00:00Z"));
        assertThat(result.draft().cases()).singleElement().satisfies(draftCase -> {
            assertThat(draftCase.annotationStatus()).isEqualTo("PENDING");
            assertThat(draftCase.shouldRefuse()).isNull();
            assertThat(draftCase.relevantEvidenceIds()).isEmpty();
            assertThat(draftCase.expectedCitationIds()).isEmpty();
            assertThat(draftCase.id()).startsWith("case-").doesNotContain("private-qa-case");
            assertThat(draftCase.workspaceId()).startsWith("workspace-")
                    .doesNotContain("workspace-private");
            assertThat(draftCase.query()).contains("token=[REDACTED]", "[EMAIL]");
            assertThat(draftCase.rawInputFingerprint()).startsWith("raw-input-");
            assertThat(draftCase.candidates()).hasSize(2);
            assertThat(draftCase.candidates()).extracting(item -> item.withinAllowedScope())
                    .containsExactly(true, false);
            assertThat(draftCase.candidates()).allSatisfy(candidate -> {
                assertThat(candidate.evidenceId()).startsWith("evidence-");
                assertThat(candidate.sourceId()).startsWith("source-");
                assertThat(candidate.citationIds()).singleElement()
                        .asString().startsWith("citation-");
            });
            assertThat(draftCase.candidates().get(0).content())
                    .contains("[PHONE]", "[IP]", "[PATH]");
        });
        assertThat(result.redactionStats().totalCount()).isGreaterThanOrEqualTo(7);
        String serialized = Files.readString(output);
        assertThat(serialized)
                .doesNotContain(
                        "workspace-private", "source-allowed", "source-denied",
                        "chunk-allowed", "chunk-out-of-scope", "abcdefgh1234",
                        "alice@example.com", "13800138000", "10.1.2.3",
                        "D:\\private", "D:/java-projects")
                .contains("PENDING", "[REDACTED]", "[EMAIL]", "[PHONE]", "[IP]", "[PATH]");
        verify(searchPort).search(
                "workspace-private",
                "spring secret token=abcdefgh1234 alice@example.com",
                5);
    }

    @Test
    void shouldRejectUnscopedOrOversizedAnnotationRequestsBeforeSearching() {
        ChunkSearchPort searchPort = mock(ChunkSearchPort.class);
        QaGoldAnnotationDraftCapture capture = new QaGoldAnnotationDraftCapture(
                searchPort,
                new RetrievalSnapshotSanitizer(),
                new ObjectMapper().findAndRegisterModules(),
                Clock.systemUTC());
        QaGoldAnnotationRequest unscoped = new QaGoldAnnotationRequest(
                QaGoldAnnotationDraftCapture.REQUEST_SCHEMA_VERSION,
                "dataset",
                5,
                List.of(new QaGoldAnnotationRequest.CaseRequest(
                        "case", "workspace", "query", List.of(), 2)));
        QaGoldAnnotationRequest oversizedTopK = new QaGoldAnnotationRequest(
                QaGoldAnnotationDraftCapture.REQUEST_SCHEMA_VERSION,
                "dataset",
                5,
                List.of(new QaGoldAnnotationRequest.CaseRequest(
                        "case", "workspace", "query", List.of("source"), 6)));

        assertThatThrownBy(() -> capture.capture(unscoped, "0123456789abcdef-salt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowedSourceIds");
        assertThatThrownBy(() -> capture.capture(oversizedTopK, "0123456789abcdef-salt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK");
        org.mockito.Mockito.verifyNoInteractions(searchPort);
    }
}
