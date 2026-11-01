package com.noteweave.answer;

import com.noteweave.answer.generation.GenerationContract;
import java.util.List;

public record AnswerGenerationMaterial(
        String messageId,
        String workspaceId,
        String content,
        List<String> citationLines,
        int maximumOutputTokens,
        GenerationContract contract
) {
    public AnswerGenerationMaterial {
        citationLines = citationLines == null ? List.of() : List.copyOf(citationLines);
    }

    /** 兼容历史运行：没有冻结生成契约。 */
    public AnswerGenerationMaterial(
            String messageId,
            String workspaceId,
            String content,
            List<String> citationLines,
            int maximumOutputTokens
    ) {
        this(messageId, workspaceId, content, citationLines, maximumOutputTokens, null);
    }
}
