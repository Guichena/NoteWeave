package com.noteweave.chat.runtime.service;

import com.noteweave.chat.model.ChatRuntimeStatus;
import com.noteweave.chat.model.ChatSessionKind;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuntimeState {

    private Long sessionId;
    private Long userId;
    private Long spaceId;
    private ChatSessionKind sessionKind;
    private ChatRuntimeStatus runtimeStatus;
    private String requestId;
    private String streamId;
    private Long lastSeq;
    private Long lastAckSeq;
}
