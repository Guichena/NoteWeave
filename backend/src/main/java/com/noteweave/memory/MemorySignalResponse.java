package com.noteweave.memory;

public record MemorySignalResponse(
        String signalId,
        String workspaceId,
        String signalType,
        String sourceType,
        String sourceId,
        String signalText,
        String taskNeighborhood,
        double confidenceScore
) {
}
