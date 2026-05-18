package com.noteweave.admin.dto;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class DashboardSummaryResponse {
    private long userCount;
    private long activeUserCount;
    private long spaceCount;
    private long knowledgeBaseCount;
    private long documentCount;
    private long artifactCount;
    private long taskCount;
    private long failedTaskCount;
    private long runningTaskCount;
    private BigDecimal taskFailureRate;
    private long storageBytes;
    private long indexedDocumentCount;
    private long documentIndexFailedCount;
    private long llmCallCount;
    private long llmSuccessCount;
    private long llmTotalTokens;
}
