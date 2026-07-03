package com.noteweave.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameKnowledgeItemRequest(
        @NotBlank @Size(max = 300) String title
) {
}
