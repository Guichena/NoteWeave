package com.noteweave.chat.dto;

import com.noteweave.chat.model.ChatScopeType;
import com.noteweave.chat.model.ChatSessionType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateChatSessionRequest {

    @NotNull
    private Long spaceId;

    @NotNull
    private ChatSessionType sessionType;

    @NotNull
    private ChatScopeType scopeType;

    @NotEmpty
    private List<Long> scopeIds;

    @NotEmpty
    private String title;
}
