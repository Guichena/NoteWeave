package com.noteweave.memory.service;

import com.noteweave.chat.model.ChatSession;
import com.noteweave.chat.runtime.service.ContextReadPlan;
import com.noteweave.memory.model.MemoryItem;
import com.noteweave.memory.model.SessionSummary;
import com.noteweave.memory.model.SpaceMemory;
import com.noteweave.memory.model.UserMemory;
import com.noteweave.personal.claim.service.ClaimService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemoryContextService {

    private static final int MAX_QUESTION_CLAIMS = 6;
    private static final int MAX_RECALLED_CLAIMS = 4;

    private final SessionSummaryService sessionSummaryService;
    private final SpaceMemoryService spaceMemoryService;
    private final UserMemoryService userMemoryService;
    private final MemoryItemService memoryItemService;
    private final ClaimService claimService;

    @Transactional(readOnly = true)
    public PromptMemoryContext load(Long userId, ChatSession session, ContextReadPlan plan) {
        return load(userId, session, plan, null);
    }

    /**
     * @param queryText the current user question; when present, it enables cross-question claim
     *                  recall so prior conclusions from other research questions can surface.
     */
    @Transactional(readOnly = true)
    public PromptMemoryContext load(Long userId, ChatSession session, ContextReadPlan plan, String queryText) {
        if (session == null) {
            return PromptMemoryContext.empty();
        }
        List<String> sessionSummaries = new ArrayList<>();
        List<String> questionSummaries = new ArrayList<>();
        List<String> claimSummaries = new ArrayList<>();
        List<String> recalledClaimSummaries = new ArrayList<>();
        List<String> spaceMemories = new ArrayList<>();
        List<String> userMemories = new ArrayList<>();

        if (plan.readSessionSummary()) {
            sessionSummaries.addAll(sessionSummaryService.retrieveRelevant(userId, session.getSpaceId()).stream()
                    .map(SessionSummary::getSummary)
                    .toList());
        }
        if (plan.readQuestionMemory()) {
            if (session.getResearchQuestionId() != null) {
                questionSummaries.addAll(sessionSummaryService.retrieveByQuestion(userId, session.getResearchQuestionId()).stream()
                        .map(SessionSummary::getSummary)
                        .toList());
                // Current effective judgments for this question (superseded claims dropped).
                claimSummaries.addAll(claimService.summarizeCurrentForQuestion(session.getResearchQuestionId(), MAX_QUESTION_CLAIMS));
            }
            // Cross-question recall: prior judgments from *other* questions relevant to this query.
            recalledClaimSummaries.addAll(
                    claimService.recallRelevantAcrossQuestions(userId, queryText, session.getResearchQuestionId(), MAX_RECALLED_CLAIMS));
        }
        if (plan.readSpaceMemory()) {
            List<MemoryItem> eligibleSpaceItems = memoryItemService.listContextEligible(userId, session.getSpaceId());
            SpaceMemory spaceMemory = spaceMemoryService.get(userId, session.getSpaceId());
            if (!eligibleSpaceItems.isEmpty() && spaceMemory != null && spaceMemory.getSummary() != null && !spaceMemory.getSummary().isBlank()) {
                spaceMemories.add(spaceMemory.getSummary());
            }
            spaceMemories.addAll(eligibleSpaceItems.stream()
                    .map(MemoryItem::getSummary)
                    .limit(3)
                    .toList());
        }
        if (plan.readUserMemory()) {
            List<MemoryItem> eligibleUserItems = memoryItemService.listContextEligible(userId, null);
            UserMemory userMemory = userMemoryService.get(userId);
            if (!eligibleUserItems.isEmpty() && userMemory != null && userMemory.getSummary() != null && !userMemory.getSummary().isBlank()) {
                userMemories.add(userMemory.getSummary());
            }
            userMemories.addAll(eligibleUserItems.stream()
                    .map(MemoryItem::getSummary)
                    .limit(3)
                    .toList());
        }
        return new PromptMemoryContext(claimSummaries, recalledClaimSummaries, questionSummaries, sessionSummaries, spaceMemories, userMemories);
    }
}
