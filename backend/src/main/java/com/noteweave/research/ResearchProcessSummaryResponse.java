package com.noteweave.research;

public record ResearchProcessSummaryResponse(
        int sourceScopeCount,
        ResearchSearchReadTimelineResponse searchReadTimeline,
        ResearchSourceEvidenceSummaryResponse sourceEvidenceSummary,
        ResearchAuditSummaryResponse auditSummary
) {
}
