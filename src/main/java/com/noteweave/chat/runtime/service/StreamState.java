package com.noteweave.chat.runtime.service;

import com.noteweave.chat.model.ChatRuntimeStatus;
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
public class StreamState {

    private String streamId;
    private ChatRuntimeStatus status;
    private String partialContent;
}
