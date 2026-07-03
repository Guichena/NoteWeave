package com.noteweave.chat;

import com.noteweave.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/conversations/{conversationId}/messages")
    ApiResponse<SendMessageResponse> sendMessage(
            @PathVariable String conversationId,
            @Valid @RequestBody SendMessageRequest request
    ) {
        return ApiResponse.success(chatService.sendMessage(conversationId, request));
    }

    @GetMapping(value = "/chat/requests/{assistantRequestId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    String stream(@PathVariable String assistantRequestId) {
        return chatService.stream(assistantRequestId);
    }
}
