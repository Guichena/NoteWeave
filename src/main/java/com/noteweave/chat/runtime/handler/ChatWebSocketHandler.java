package com.noteweave.chat.runtime.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.runtime.protocol.ClientEventEnvelope;
import com.noteweave.chat.runtime.service.ChatRuntimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final ChatRuntimeService chatRuntimeService;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        chatRuntimeService.onConnect(resolveUserId(session), session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        ClientEventEnvelope event = objectMapper.readValue(message.getPayload(), ClientEventEnvelope.class);
        chatRuntimeService.handleClientEvent(resolveUserId(session), session, event);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // no-op
    }

    private Long resolveUserId(WebSocketSession session) {
        Object value = session.getAttributes().get("userId");
        return value instanceof Long longValue ? longValue : Long.valueOf(String.valueOf(value));
    }
}
