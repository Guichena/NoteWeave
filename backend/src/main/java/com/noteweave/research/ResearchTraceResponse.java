package com.noteweave.research;

import java.time.Instant;
import java.util.Map;

public record ResearchTraceResponse(
        String traceId,
        String traceType,
        String traceMessage,
        Map<String, Object> payload,
        Instant createdAt
) {
}
