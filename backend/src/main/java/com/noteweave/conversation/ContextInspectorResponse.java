package com.noteweave.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

public record ContextInspectorResponse(
        String executionKind,
        String runId,
        String replayAvailability,
        JsonNode retrievalConfig,
        JsonNode snapshot,
        Map<String, JsonNode> selectedIdentities
) {
}
