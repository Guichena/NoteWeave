package com.noteweave.memory;

import java.time.Instant;
import java.util.List;

/** 用户停用的记忆：记忆页据此展示已停用列表，并提供重新启用入口。 */
public record RevokedMemoryItemResponse(
        String memoryItemId,
        String revisionId,
        String displayText,
        String candidateType,
        List<String> taskNeighborhoods,
        Instant revokedAt
) {
    public RevokedMemoryItemResponse {
        taskNeighborhoods = taskNeighborhoods == null ? List.of() : List.copyOf(taskNeighborhoods);
    }
}
