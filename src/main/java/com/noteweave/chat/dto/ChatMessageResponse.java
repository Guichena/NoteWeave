package com.noteweave.chat.dto;

import com.noteweave.chat.model.ChatMessageRole;
import com.noteweave.chat.model.ChatMessageStatus;
import com.noteweave.chat.model.ChatMessageType;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ChatMessageResponse {
    private Long id;
    private Long sessionId;
    private Integer messageSeq;
    private ChatMessageRole role;
    private String content;
    private ChatMessageType messageType;
    private ChatMessageStatus status;
    private Long artifactId;
    private String requestId;
    private String errorCode;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
