package com.noteweave.memory;

import java.util.List;

public record MemoryCompileHintsResponse(
        List<String> styleConstraints,
        List<String> structureConstraints,
        List<String> terminologyPolicy,
        List<String> forbiddenPatterns,
        List<String> interactionPolicy,
        List<String> reviewChecklist
) {
    static MemoryCompileHintsResponse from(
            MemorySignalService.MemoryCompileHints hints
    ) {
        return new MemoryCompileHintsResponse(
                hints.styleConstraints(),
                hints.structureConstraints(),
                hints.terminologyPolicy(),
                hints.forbiddenPatterns(),
                hints.interactionPolicy(),
                hints.reviewChecklist()
        );
    }
}
