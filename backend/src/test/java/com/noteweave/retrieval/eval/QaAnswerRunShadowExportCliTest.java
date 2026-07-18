package com.noteweave.retrieval.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class QaAnswerRunShadowExportCliTest {

    @Test
    void shouldDispatchAnswerRunExportWithSeparateAnnotationAndRunMap() throws Exception {
        QaAnswerRunShadowExportService service = mock(QaAnswerRunShadowExportService.class);
        RetrievalShadowSnapshot snapshot = new RetrievalShadowSnapshot(
                RetrievalShadowComparator.SHADOW_SCHEMA_VERSION,
                "online-shadow-1",
                List.of(new RetrievalShadowSnapshot.CaseRanking("case", 1, 0, List.of()))
        );
        when(service.exportAndWrite(any(), any(), any(), eq("0123456789abcdef")))
                .thenReturn(new QaAnswerRunShadowExportService.ExportResult(snapshot, 1));

        QaAnswerRunShadowExportCli.CliResult result = QaAnswerRunShadowExportCli.runCommand(
                new String[]{
                        "export-answer-runs",
                        "annotation.json",
                        "run-map.json",
                        "shadow.json"
                },
                service,
                "0123456789abcdef"
        );

        assertThat(result.operation()).isEqualTo("export-answer-runs");
        assertThat(result.snapshotVersion()).isEqualTo("online-shadow-1");
        assertThat(result.caseCount()).isEqualTo(1);
        verify(service).exportAndWrite(
                Path.of("annotation.json"),
                Path.of("run-map.json"),
                Path.of("shadow.json"),
                "0123456789abcdef");
    }

    @Test
    void shouldRejectUnknownOrIncompleteCommand() {
        QaAnswerRunShadowExportService service = mock(QaAnswerRunShadowExportService.class);

        assertThatThrownBy(() -> QaAnswerRunShadowExportCli.runCommand(
                new String[]{"export-answer-runs", "annotation.json"}, service,
                "0123456789abcdef"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("annotation-request.json");
        assertThatThrownBy(() -> QaAnswerRunShadowExportCli.runCommand(
                new String[]{"unknown", "a", "b", "c"}, service,
                "0123456789abcdef"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
