package com.noteweave.artifact;

import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

public record ArtifactNodeTraceResponse(
        String nodeId,
        String skillKey,
        String outputSummary,
        String verificationStatus,
        List<String> verificationChecks,
        List<String> repairActions,
        boolean repaired,
        String nodeKind,
        String purpose,
        String promptRecipeId,
        int llmCalls,
        List<String> validationErrors,
        List<String> inputFields,
        List<String> outputFields,
        Map<String, Object> executionTrace
) {
    public ArtifactNodeTraceResponse {
        nodeKind = nodeKind == null ? "" : nodeKind;
        purpose = purpose == null ? "" : purpose;
        promptRecipeId = promptRecipeId == null ? "" : promptRecipeId;
        validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
        inputFields = inputFields == null ? List.of() : List.copyOf(inputFields);
        outputFields = outputFields == null ? List.of() : List.copyOf(outputFields);
        executionTrace = executionTrace == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(executionTrace));
    }
}
