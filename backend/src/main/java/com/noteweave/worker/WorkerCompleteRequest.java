package com.noteweave.worker;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public record WorkerCompleteRequest(
        @NotBlank @Size(max = 64) String resultType,
        @NotBlank @Size(max = 300) String resultTitle,
        Map<String, Object> resultPayload,
        @Size(max = 4000) String traceSummary,
        List<Map<String, Object>> citations
) {
}
