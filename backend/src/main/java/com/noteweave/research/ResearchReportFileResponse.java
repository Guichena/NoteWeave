package com.noteweave.research;

public record ResearchReportFileResponse(
        String objectKey,
        String fileName,
        String mimeType,
        long contentSize,
        String sha256
) {
}
