package com.noteweave.artifact.dto;

import com.noteweave.personal.card.dto.SynthesisCardResponse;
import java.util.List;
import java.util.Map;
import lombok.Builder;

@Builder
public record DistillArtifactResponse(
        Long proposalId,
        String cardType,
        Long artifactId,
        Long artifactVersionId,
        String title,
        String summary,
        List<String> insights,
        List<Map<String, Object>> evidenceQuotes,
        boolean confirmed,
        SynthesisCardResponse synthesisCard
) {
}
