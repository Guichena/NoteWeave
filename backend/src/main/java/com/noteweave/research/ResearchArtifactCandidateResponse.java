package com.noteweave.research;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResearchArtifactCandidateResponse(
        String artifactType,
        String artifactVersion,
        String title,
        String question,
        String generatedBy,
        String generatedRefType,
        String generatedRefId,
        String answerStatus,
        String confidenceLabel,
        String coverageLabel,
        String sourceBasis,
        String answerText,
        String contentMarkdown,
        Map<String, Object> reportStructure,
        Map<String, Object> sourceFoundation,
        Map<String, Object> researchIntent,
        Map<String, Object> closedLoopState,
        ResearchResumeContextSummaryResponse resumeContextSummary,
        int citationCount,
        List<Map<String, Object>> citations
) {
}
