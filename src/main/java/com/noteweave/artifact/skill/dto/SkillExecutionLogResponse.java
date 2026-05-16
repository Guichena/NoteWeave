package com.noteweave.artifact.skill.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import lombok.Builder;

@Builder
public record SkillExecutionLogResponse(
        Long id,
        Long taskId,
        Long artifactId,
        Long artifactVersionId,
        String skillName,
        JsonNode input,
        JsonNode output,
        String status,
        String errorMessage,
        Long latencyMs,
        String modelName,
        String promptVersion,
        Integer inputTokens,
        Integer outputTokens,
        LocalDateTime createdAt
) {
}
