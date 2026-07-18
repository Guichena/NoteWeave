package com.noteweave.artifact;

import java.util.List;

public record ArtifactExportTraceResponse(
        String status,
        String format,
        String fileName,
        String downloadPath,
        String executionMode,
        List<String> notes
) {
}
