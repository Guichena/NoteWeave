package com.noteweave.conversation;

import com.noteweave.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/workspaces/{workspaceId}/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    ApiResponse<ConversationResponse> createConversation(
            @PathVariable String workspaceId,
            @Valid @RequestBody CreateConversationRequest request
    ) {
        return ApiResponse.success(conversationService.createConversation(workspaceId, request));
    }
}
