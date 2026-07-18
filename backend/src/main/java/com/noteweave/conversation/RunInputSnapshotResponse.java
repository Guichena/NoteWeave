package com.noteweave.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record RunInputSnapshotResponse(
        String snapshotId,
        String executionKind,
        String runId,
        String conversationId,
        String queryMessageId,
        String assistantMessageId,
        String requestedTurnMode,
        String historyHeadMessageId,
        int conversationCutoffSeq,
        JsonNode retrievalConfig,
        JsonNode snapshot,
        String compilerVersion,
        String promptVersion,
        JsonNode tokenBudget,
        String replayAvailability,
        Instant createdAt
) {
}
