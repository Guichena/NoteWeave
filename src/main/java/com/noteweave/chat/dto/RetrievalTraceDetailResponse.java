package com.noteweave.chat.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

@Builder
public record RetrievalTraceDetailResponse(
        Long id,
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
        String traceJson,
        LocalDateTime createdAt,
        List<RetrievalTraceItemResponse> items
) {
}
