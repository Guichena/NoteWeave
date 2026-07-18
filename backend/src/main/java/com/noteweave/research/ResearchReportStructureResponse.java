package com.noteweave.research;

import java.util.List;
import java.util.Map;

public record ResearchReportStructureResponse(
        Map<String, Object> researchQuestion,
        ResearchIntentResponse researchIntent,
        Map<String, Object> intentCompletionContract,
        ResearchIntentAlignmentResponse researchIntentAlignment,
        List<String> keyFindings,
        List<Map<String, Object>> verifiedFindings,
        List<Map<String, Object>> evidenceLedger,
        Map<String, Object> closedLoopState,
        ResearchCounterfactualSummaryResponse counterfactualSummary,
        Map<String, Object> conflictAndCounterfactualReview,
        Map<String, Object> recoveryStatus,
        Map<String, Object> finalAnswer,
        Map<String, Object> sourceFoundation,
        List<String> nextActions,
        Map<String, Object> resumeCheckpoint,
        String recoveryMode,
        List<String> controlNotes
) {
}
