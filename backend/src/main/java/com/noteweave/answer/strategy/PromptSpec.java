package com.noteweave.answer.strategy;

import java.util.List;

public record PromptSpec(
        String systemPrompt,
        String userPrompt,
        List<String> referencedEvidenceIds,
        String promptVersion,
        boolean refusal
) {
    public PromptSpec {
        referencedEvidenceIds = referencedEvidenceIds == null
                ? List.of()
                : List.copyOf(referencedEvidenceIds);
    }

    public PromptSpec(
            String systemPrompt,
            String userPrompt,
            List<String> referencedEvidenceIds,
            String promptVersion
    ) {
        this(systemPrompt, userPrompt, referencedEvidenceIds, promptVersion, false);
    }
}
