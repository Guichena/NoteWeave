package com.noteweave.artifact;

import java.util.List;

public record ArtifactSectionEvidenceTraceResponse(
        String sectionHeading,
        List<String> sourceRefs,
        List<String> sourceIds,
        String evidenceStatus
) {
}
