package com.noteweave.memory;

public record ExecutionObservation(
        String observationId,
        String workspaceId,
        String scope,
        String slotKey,
        String displayText,
        String provenanceType,
        String provenanceRef
) {
}
