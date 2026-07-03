package com.noteweave.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record KnowledgeItemRequest(
        @NotBlank @Pattern(regexp = "NOTE|WIKI") String itemType,
        @NotBlank @Size(max = 300) String title,
        @NotBlank @Size(max = 20000) String content,
        @Size(max = 36) String sourceMessageId
) {
}
