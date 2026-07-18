package com.noteweave.retrieval.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetrievalEvaluationCliTest {

    @Test
    void shouldRouteCaptureDraftWithoutPrintingSensitiveInputs() throws Exception {
        QaGoldAnnotationDraftCapture capture = mock(QaGoldAnnotationDraftCapture.class);
        QaGoldAnnotationCompiler compiler = mock(QaGoldAnnotationCompiler.class);
        QaGoldAnnotationDraft draft = new QaGoldAnnotationDraft(
                QaGoldAnnotationDraftCapture.DRAFT_SCHEMA_VERSION,
                "dataset-annotation-draft",
                12,
                Instant.parse("2026-07-14T00:00:00Z"),
                List.of()
        );
        when(capture.captureAndWrite(
                Path.of("private-request.json"), Path.of("draft.json"), "0123456789abcdef-salt"))
                .thenReturn(new QaGoldAnnotationDraftCapture.CaptureResult(draft, zeroStats()));

        var result = RetrievalEvaluationCli.runCommand(
                new String[]{"capture-draft", "private-request.json", "draft.json"},
                capture,
                compiler,
                "0123456789abcdef-salt"
        );

        assertThat(result.operation()).isEqualTo("capture-draft");
        assertThat(result.datasetVersion()).isEqualTo("dataset-annotation-draft");
        assertThat(result.snapshotVersion()).isEmpty();
        verify(capture).captureAndWrite(
                Path.of("private-request.json"), Path.of("draft.json"), "0123456789abcdef-salt");
    }

    @Test
    void shouldRouteReviewedCompilationToSanitizedOutputs() throws Exception {
        QaGoldAnnotationDraftCapture capture = mock(QaGoldAnnotationDraftCapture.class);
        QaGoldAnnotationCompiler compiler = mock(QaGoldAnnotationCompiler.class);
        RetrievalGoldSet goldSet = new RetrievalGoldSet(
                RetrievalBenchmarkReplay.GOLD_SCHEMA_VERSION,
                "dataset-reviewed-sanitized",
                List.of()
        );
        RetrievalShadowSnapshot shadow = new RetrievalShadowSnapshot(
                RetrievalShadowComparator.SHADOW_SCHEMA_VERSION,
                "snapshot-v1",
                List.of()
        );
        when(compiler.compileAndWrite(
                Path.of("private-request.json"),
                Path.of("reviewed.json"),
                Path.of("gold.json"),
                Path.of("shadow.json"),
                "snapshot-v1",
                "0123456789abcdef-salt"
        )).thenReturn(new QaGoldAnnotationCompiler.CompilationResult(goldSet, shadow, zeroStats()));

        var result = RetrievalEvaluationCli.runCommand(
                new String[]{
                        "compile-reviewed", "private-request.json", "reviewed.json",
                        "gold.json", "shadow.json", "snapshot-v1"
                },
                capture,
                compiler,
                "0123456789abcdef-salt"
        );

        assertThat(result.operation()).isEqualTo("compile-reviewed");
        assertThat(result.datasetVersion()).isEqualTo("dataset-reviewed-sanitized");
        assertThat(result.snapshotVersion()).isEqualTo("snapshot-v1");
        verify(compiler).compileAndWrite(
                Path.of("private-request.json"),
                Path.of("reviewed.json"),
                Path.of("gold.json"),
                Path.of("shadow.json"),
                "snapshot-v1",
                "0123456789abcdef-salt");
    }

    @Test
    void shouldRejectUnknownOrIncompleteCommands() {
        QaGoldAnnotationDraftCapture capture = mock(QaGoldAnnotationDraftCapture.class);
        QaGoldAnnotationCompiler compiler = mock(QaGoldAnnotationCompiler.class);

        assertThatThrownBy(() -> RetrievalEvaluationCli.runCommand(
                new String[]{"unknown"}, capture, compiler, "0123456789abcdef-salt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Usage:");
        assertThatThrownBy(() -> RetrievalEvaluationCli.runCommand(
                new String[]{"capture-draft", "request-only.json"},
                capture, compiler, "0123456789abcdef-salt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Usage:");
    }

    private RetrievalSnapshotSanitizer.RedactionStats zeroStats() {
        return new RetrievalSnapshotSanitizer.RedactionStats(0, 0, 0, 0, 0, 0, 0);
    }
}
