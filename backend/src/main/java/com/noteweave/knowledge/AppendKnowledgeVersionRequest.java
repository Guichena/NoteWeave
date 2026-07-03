package com.noteweave.knowledge;

import jakarta.validation.constraints.Size;
import java.util.List;

public record AppendKnowledgeVersionRequest(
        @Size(max = 20000) String content,
        @Size(max = 36) String sourceMessageId,
        List<@Size(max = 36) String> citationIds
) {
}
