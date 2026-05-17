package com.noteweave.memory.controller;

import com.noteweave.chat.service.ChatSessionService;
import com.noteweave.common.api.ApiResponse;
import com.noteweave.common.security.CurrentUserProvider;
import com.noteweave.memory.dto.MemoryItemRequest;
import com.noteweave.memory.dto.MemoryItemResponse;
import com.noteweave.memory.dto.MemoryUpsertResponse;
import com.noteweave.memory.dto.MemoryWriteToggleResponse;
import com.noteweave.memory.dto.SessionSummaryResponse;
import com.noteweave.memory.dto.SpaceMemoryResponse;
import com.noteweave.memory.dto.UserMemoryResponse;
import com.noteweave.memory.model.MemoryItem;
import com.noteweave.memory.model.SpaceMemory;
import com.noteweave.memory.model.UserMemory;
import com.noteweave.memory.service.MemoryItemService;
import com.noteweave.memory.service.SessionSummaryService;
import com.noteweave.memory.service.SpaceMemoryService;
import com.noteweave.memory.service.UserMemoryService;
import com.noteweave.permission.service.ResourceAccessService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MemoryController {

    private final CurrentUserProvider currentUserProvider;
    private final ResourceAccessService resourceAccessService;
    private final MemoryItemService memoryItemService;
    private final SpaceMemoryService spaceMemoryService;
    private final UserMemoryService userMemoryService;
    private final SessionSummaryService sessionSummaryService;
    private final ChatSessionService chatSessionService;

    @GetMapping("/spaces/{spaceId}/memory")
    public ApiResponse<SpaceMemoryResponse> getSpaceMemory(@PathVariable Long spaceId) {
        Long userId = currentUserProvider.getCurrentUserId();
        resourceAccessService.requireViewSpace(userId, spaceId);
        SpaceMemory spaceMemory = spaceMemoryService.get(userId, spaceId);
        UserMemory userMemory = userMemoryService.getOrCreate(userId);
        return ApiResponse.success(SpaceMemoryResponse.builder()
                .spaceId(spaceId)
                .writeEnabled(userMemory.isMemoryWriteEnabled())
                .summary(spaceMemory == null ? null : spaceMemory.getSummary())
                .items(memoryItemService.listActive(userId, spaceId).stream().map(this::toResponse).toList())
                .build());
    }

    @PutMapping("/spaces/{spaceId}/memory")
    public ApiResponse<MemoryUpsertResponse> upsertSpaceMemory(
            @PathVariable Long spaceId,
            @Valid @RequestBody MemoryItemRequest request
    ) {
        Long userId = currentUserProvider.getCurrentUserId();
        resourceAccessService.requireViewSpace(userId, spaceId);
        MemoryItem item = memoryItemService.upsertManual(userId, spaceId, request);
        List<MemoryItem> active = memoryItemService.listContextEligible(userId, spaceId);
        spaceMemoryService.refreshFromItems(userId, spaceId, active);
        return ApiResponse.success(MemoryUpsertResponse.builder()
                .writeEnabled(userMemoryService.isWriteEnabled(userId))
                .summary(spaceMemoryService.get(userId, spaceId) == null ? null : spaceMemoryService.get(userId, spaceId).getSummary())
                .item(toResponse(item))
                .build());
    }

    @DeleteMapping("/spaces/{spaceId}/memory/{memoryItemId}")
    public ApiResponse<Void> deleteSpaceMemory(@PathVariable Long spaceId, @PathVariable Long memoryItemId) {
        Long userId = currentUserProvider.getCurrentUserId();
        resourceAccessService.requireViewSpace(userId, spaceId);
        memoryItemService.delete(userId, spaceId, memoryItemId);
        spaceMemoryService.refreshFromItems(userId, spaceId, memoryItemService.listContextEligible(userId, spaceId));
        return ApiResponse.success(null);
    }

    @GetMapping("/users/me/memory")
    public ApiResponse<UserMemoryResponse> getUserMemory() {
        Long userId = currentUserProvider.getCurrentUserId();
        UserMemory userMemory = userMemoryService.getOrCreate(userId);
        return ApiResponse.success(UserMemoryResponse.builder()
                .writeEnabled(userMemory.isMemoryWriteEnabled())
                .summary(userMemory.getSummary())
                .items(memoryItemService.listActive(userId, null).stream().map(this::toResponse).toList())
                .build());
    }

    @PutMapping("/users/me/memory")
    public ApiResponse<MemoryUpsertResponse> upsertUserMemory(@Valid @RequestBody MemoryItemRequest request) {
        Long userId = currentUserProvider.getCurrentUserId();
        MemoryItem item = memoryItemService.upsertManual(userId, null, request);
        List<MemoryItem> active = memoryItemService.listContextEligible(userId, null);
        userMemoryService.refreshFromItems(userId, active);
        UserMemory userMemory = userMemoryService.getOrCreate(userId);
        return ApiResponse.success(MemoryUpsertResponse.builder()
                .writeEnabled(userMemory.isMemoryWriteEnabled())
                .summary(userMemory.getSummary())
                .item(toResponse(item))
                .build());
    }

    @DeleteMapping("/users/me/memory/{memoryItemId}")
    public ApiResponse<Void> deleteUserMemory(@PathVariable Long memoryItemId) {
        Long userId = currentUserProvider.getCurrentUserId();
        memoryItemService.delete(userId, null, memoryItemId);
        userMemoryService.refreshFromItems(userId, memoryItemService.listContextEligible(userId, null));
        return ApiResponse.success(null);
    }

    @PostMapping("/users/me/memory/disable")
    public ApiResponse<MemoryWriteToggleResponse> disableWriteback() {
        Long userId = currentUserProvider.getCurrentUserId();
        UserMemory userMemory = userMemoryService.setWriteEnabled(userId, false);
        return ApiResponse.success(MemoryWriteToggleResponse.builder()
                .writeEnabled(userMemory.isMemoryWriteEnabled())
                .build());
    }

    @PostMapping("/users/me/memory/enable")
    public ApiResponse<MemoryWriteToggleResponse> enableWriteback() {
        Long userId = currentUserProvider.getCurrentUserId();
        UserMemory userMemory = userMemoryService.setWriteEnabled(userId, true);
        return ApiResponse.success(MemoryWriteToggleResponse.builder()
                .writeEnabled(userMemory.isMemoryWriteEnabled())
                .build());
    }

    @GetMapping("/chat/sessions/{sessionId}/summaries")
    public ApiResponse<List<SessionSummaryResponse>> getSessionSummaries(@PathVariable Long sessionId) {
        Long userId = currentUserProvider.getCurrentUserId();
        var session = chatSessionService.getRequiredActiveSession(sessionId);
        resourceAccessService.requireViewSpace(userId, session.getSpaceId());
        return ApiResponse.success(sessionSummaryService.listBySession(userId, sessionId).stream()
                .map(summary -> SessionSummaryResponse.builder()
                        .id(summary.getId())
                        .sessionId(summary.getSessionId())
                        .topic(summary.getTopic())
                        .summary(summary.getSummary())
                        .importanceScore(summary.getImportanceScore())
                        .confidenceScore(summary.getConfidenceScore())
                        .pin(summary.isPin())
                        .expiresAt(summary.getExpiresAt())
                        .createdAt(summary.getCreatedAt())
                        .build())
                .toList());
    }

    private MemoryItemResponse toResponse(MemoryItem item) {
        return MemoryItemResponse.builder()
                .id(item.getId())
                .memoryType(item.getMemoryType())
                .topic(item.getTopic())
                .summary(item.getSummary())
                .sourceType(item.getSourceType())
                .sourceId(item.getSourceId())
                .importanceScore(item.getImportanceScore())
                .confidenceScore(item.getConfidenceScore())
                .pin(item.isPin())
                .expiresAt(item.getExpiresAt())
                .createdAt(item.getCreatedAt())
                .updatedAt(item.getUpdatedAt())
                .build();
    }
}
