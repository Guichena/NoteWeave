package com.noteweave.conversation;

import com.fasterxml.jackson.databind.JsonNode;

public record RunReplayResponse(
        String executionKind,
        String runId,
        String replayAvailability,
        boolean fullReplayAvailable,
        JsonNode frozenSnapshot
) {
}
