package com.noteweave.artifact;

import java.util.List;

public record ArtifactEvidenceCoverageTraceResponse(
        String status,
        String requiredCitationDensity,
        int sectionCount,
        int coveredSectionCount,
        double coverageRatio,
        List<String> supportingSourceIds,
        List<ArtifactSectionEvidenceTraceResponse> sectionEvidence,
        List<String> sectionsMissingEvidence,
        List<String> notes
) {
}
