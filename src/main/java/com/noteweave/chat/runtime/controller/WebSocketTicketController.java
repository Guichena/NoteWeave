package com.noteweave.chat.runtime.controller;

import com.noteweave.chat.runtime.dto.WebSocketTicketResponse;
import com.noteweave.chat.runtime.service.WebSocketTicketService;
import com.noteweave.common.api.ApiResponse;
import com.noteweave.common.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class WebSocketTicketController {

    private final CurrentUserProvider currentUserProvider;
    private final WebSocketTicketService webSocketTicketService;

    @PostMapping("/ws-ticket")
    public ApiResponse<WebSocketTicketResponse> createTicket() {
        String ticket = webSocketTicketService.createTicket(currentUserProvider.getCurrentUserId());
        return ApiResponse.success(WebSocketTicketResponse.builder()
                .ticket(ticket)
                .expiresIn(60)
                .webSocketUrl("/ws/chat/" + ticket)
                .build());
    }
}
