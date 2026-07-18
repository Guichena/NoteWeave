package com.noteweave.answer;

import java.util.List;

public record AnswerGenerationMaterial(
        String messageId,
        String workspaceId,
        String content,
        List<String> citationLines,
        int maximumOutputTokens
) {
}
