package com.noteweave.chat.controller;

import com.noteweave.chat.dto.AnswerFeedbackRequest;
import com.noteweave.chat.dto.AnswerFeedbackResponse;
import com.noteweave.chat.dto.ChatMessageResponse;
import com.noteweave.chat.dto.ChatSessionResponse;
import com.noteweave.chat.dto.CreateChatSessionRequest;
import com.noteweave.chat.dto.TeamAskRequest;
import com.noteweave.chat.dto.TeamAskResponse;
import com.noteweave.chat.service.AnswerFeedbackService;
import com.noteweave.chat.service.ChatSessionService;
import com.noteweave.chat.service.TeamChatService;
import com.noteweave.citation.dto.CitationResponse;
import com.noteweave.citation.service.CitationService;
import com.noteweave.common.api.ApiResponse;
import com.noteweave.common.security.CurrentUserProvider;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ChatController {

    private final ChatSessionService chatSessionService;
    private final TeamChatService teamChatService;
    private final CitationService citationService;
    private final AnswerFeedbackService answerFeedbackService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping("/chat/sessions")
    public ApiResponse<ChatSessionResponse> createSession(@Valid @RequestBody CreateChatSessionRequest request) {
        return ApiResponse.success(chatSessionService.createTeamSession(currentUserProvider.getCurrentUserId(), request));
    }

    @GetMapping("/spaces/{spaceId}/chat-sessions")
    public ApiResponse<List<ChatSessionResponse>> listSessions(@PathVariable Long spaceId) {
        return ApiResponse.success(chatSessionService.listBySpace(currentUserProvider.getCurrentUserId(), spaceId));
    }

    @GetMapping("/chat/sessions/{sessionId}")
    public ApiResponse<ChatSessionResponse> getSession(@PathVariable Long sessionId) {
        return ApiResponse.success(chatSessionService.getSession(currentUserProvider.getCurrentUserId(), sessionId));
    }

    @GetMapping("/chat/sessions/{sessionId}/messages")
    public ApiResponse<List<ChatMessageResponse>> listMessages(@PathVariable Long sessionId) {
        return ApiResponse.success(chatSessionService.listMessages(currentUserProvider.getCurrentUserId(), sessionId));
    }

    @PostMapping("/chat/sessions/{sessionId}/messages")
    public ApiResponse<TeamAskResponse> ask(@PathVariable Long sessionId, @Valid @RequestBody TeamAskRequest request) {
        return ApiResponse.success(teamChatService.ask(currentUserProvider.getCurrentUserId(), sessionId, request));
    }

    @PostMapping("/chat/sessions/{sessionId}/convert-to-formal")
    public ApiResponse<ChatSessionResponse> convertToFormal(@PathVariable Long sessionId) {
        return ApiResponse.success(chatSessionService.convertDraftToFormal(currentUserProvider.getCurrentUserId(), sessionId));
    }

    @PostMapping("/chat/sessions/{sessionId}/discard-draft")
    public ApiResponse<ChatSessionResponse> discardDraft(@PathVariable Long sessionId) {
        return ApiResponse.success(chatSessionService.discardDraft(currentUserProvider.getCurrentUserId(), sessionId));
    }

    @GetMapping("/chat/messages/{messageId}/citations")
    public ApiResponse<List<CitationResponse>> listCitations(@PathVariable Long messageId) {
        Long userId = currentUserProvider.getCurrentUserId();
        Long spaceId = chatSessionService.getSessionByMessageId(userId, messageId).getSpaceId();
        return ApiResponse.success(citationService.listByMessage(currentUserProvider.getCurrentUserId(), messageId, spaceId));
    }

    @PostMapping("/chat/messages/{messageId}/feedback")
    public ApiResponse<AnswerFeedbackResponse> submitFeedback(
            @PathVariable Long messageId,
            @Valid @RequestBody AnswerFeedbackRequest request
    ) {
        return ApiResponse.success(answerFeedbackService.submit(currentUserProvider.getCurrentUserId(), messageId, request));
    }
}
