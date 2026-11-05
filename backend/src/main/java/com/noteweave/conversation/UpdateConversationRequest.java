package com.noteweave.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 会话重命名请求：目前只允许修改标题。 */
public record UpdateConversationRequest(
        @NotBlank @Size(max = 200) String title
) {
}
