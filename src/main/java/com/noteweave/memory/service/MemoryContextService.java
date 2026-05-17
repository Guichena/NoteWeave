package com.noteweave.memory.service;

import com.noteweave.chat.model.ChatSession;
import com.noteweave.chat.runtime.service.ContextReadPlan;
import com.noteweave.memory.model.MemoryItem;
import com.noteweave.memory.model.SessionSummary;
import com.noteweave.memory.model.SpaceMemory;
import com.noteweave.memory.model.UserMemory;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemoryContextService {

    private final SessionSummaryService sessionSummaryService;
    private final SpaceMemoryService spaceMemoryService;
    private final UserMemoryService userMemoryService;
    private final MemoryItemService memoryItemService;

    @Transactional(readOnly = true)
    public PromptMemoryContext load(Long userId, ChatSession session, ContextReadPlan plan) {
        if (session == null) {
            return PromptMemoryContext.empty();
        }
        List<String> sessionSummaries = new ArrayList<>();
        List<String> spaceMemories = new ArrayList<>();
        List<String> userMemories = new ArrayList<>();

        if (plan.readSessionSummary()) {
            sessionSummaries.addAll(sessionSummaryService.retrieveRelevant(userId, session.getSpaceId()).stream()
                    .map(SessionSummary::getSummary)
                    .toList());
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
        return new PromptMemoryContext(sessionSummaries, spaceMemories, userMemories);
    }
}
