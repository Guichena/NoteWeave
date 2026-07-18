package com.noteweave.research;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResearchRunArtifactResponse(
        String artifactId,
        String artifactType,
        String artifactVersion,
        String title,
        String generatedBy,
        String generatedRefType,
        String generatedRefId,
        int citationCount,
        ResearchReportFileResponse reportFile,
        SaveResearchReportSourceResponse savedReportSource
) {
}
