package com.noteweave.chat.dto;

import lombok.Builder;

@Builder
public record RetrievalTraceCreateRequest(
        Long userId,
        Long spaceId,
        Long sessionId,
        Long messageId,
        Long taskId,
        String scene,
        String queryText,
        String retrieverType,
        Integer topK,
        Long latencyMs,
        Integer retrievedChunkCount,
        String retrievalMode,
        Integer bm25Count,
        Integer vectorCount,
        Integer fusionCount,
        boolean fallbackUsed,
        String traceJson
) {
}
