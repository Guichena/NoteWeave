package com.noteweave.chat.dto;

import com.noteweave.chat.model.ChatScopeType;
import com.noteweave.chat.model.ChatSessionKind;
import com.noteweave.chat.model.ChatSessionStatus;
import com.noteweave.chat.model.ChatSessionType;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ChatSessionResponse {
    private Long id;
    private Long userId;
    private Long spaceId;
    private ChatSessionType sessionType;
    private ChatSessionKind sessionKind;
    private ChatScopeType scopeType;
    private List<Long> scopeIds;
    private String title;
    private String summary;
    private ChatSessionStatus status;
    private String runtimeStatus;
    private LocalDateTime lastActiveAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
