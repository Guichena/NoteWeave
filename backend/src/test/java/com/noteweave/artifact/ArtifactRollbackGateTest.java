package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.common.BusinessException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ArtifactRollbackGateTest {
    private final ArtifactJobService jobs = mock(ArtifactJobService.class);
    private final ArtifactExportService exports = mock(ArtifactExportService.class);
    private final ArtifactJobController controller = new ArtifactJobController(jobs, exports);
    private final ArtifactVersionDetailResponse source = version("source", 1);
    private final ArtifactVersionDetailResponse copy = version("copy", 2);

    @Test
    void missingSourceFilePreventsRollbackVersionCreation() {
        when(jobs.getVersionDetail("workspace", "job", 1)).thenReturn(source);
        when(exports.prepareRollbackFiles("source"))
                .thenThrow(new BusinessException("ARTIFACT_SOURCE_FILE_MISSING", "missing PDF"));

        assertThatThrownBy(() -> controller.rollbackArtifactVersion("workspace", "job", 1, null))
                .isInstanceOf(BusinessException.class);

        verify(jobs, never()).rollbackVersion("workspace", "job", 1, null);
    }

    @Test
    void sourceFilesAreValidatedBeforeNewVersionIsPublished() {
        List<ArtifactExportService.PreparedFile> prepared = List.of(
                new ArtifactExportService.PreparedFile("MARKDOWN", "lesson.md",
                        "text/markdown", new byte[] {1}, "digest"));
        when(jobs.getVersionDetail("workspace", "job", 1)).thenReturn(source);
        when(jobs.getVersionDetail("workspace", "job", 2)).thenReturn(copy);
        when(exports.prepareRollbackFiles("source")).thenReturn(prepared);
        when(jobs.rollbackVersion("workspace", "job", 1, null)).thenReturn(copy);

        controller.rollbackArtifactVersion("workspace", "job", 1, null);

        InOrder order = inOrder(exports, jobs);
        order.verify(exports).prepareRollbackFiles("source");
        order.verify(jobs).rollbackVersion("workspace", "job", 1, null);
        order.verify(exports).publishPreparedFiles("copy", prepared);
    }

    private ArtifactVersionDetailResponse version(String id, int number) {
        return new ArtifactVersionDetailResponse(id, "job", "bilibili_course_note_pdf", number,
                "Lesson", "# Lesson", "", List.of(), null, List.of(), Instant.EPOCH);
    }
}
