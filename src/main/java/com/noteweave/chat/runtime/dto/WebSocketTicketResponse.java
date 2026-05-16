package com.noteweave.chat.runtime.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WebSocketTicketResponse {

    private String ticket;
    private int expiresIn;
    private String webSocketUrl;
}
