package com.noteweave.rageval.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;

@Builder
public record RagEvalResultResponse(
        Long id,
        Long runId,
        Long caseId,
        Long answerMessageId,
        Long retrievalTraceId,
        Long llmCallLogId,
        BigDecimal recallAtK,
        BigDecimal mrr,
        BigDecimal citationCoverage,
        BigDecimal groundednessScore,
        BigDecimal answerQualityScore,
        Long latencyMs,
        String errorMessage,
        String answerSnapshot,
        LocalDateTime createdAt
) {
}
