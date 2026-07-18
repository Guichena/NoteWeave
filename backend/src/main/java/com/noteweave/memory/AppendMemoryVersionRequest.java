package com.noteweave.memory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record AppendMemoryVersionRequest(
        @NotBlank String canonicalStatement,
        @NotEmpty List<String> taskNeighborhoods,
        List<String> styleConstraints,
        List<String> structureConstraints,
        List<String> terminologyPolicy,
        List<String> forbiddenPatterns,
        List<String> interactionPolicy,
        List<String> reviewChecklist
) {
    public AppendMemoryVersionRequest {
        canonicalStatement = canonicalStatement == null ? null : canonicalStatement.trim();
        taskNeighborhoods = MemorySignalService.normalizeList(taskNeighborhoods);
        styleConstraints = MemorySignalService.normalizeList(styleConstraints);
        structureConstraints = MemorySignalService.normalizeList(structureConstraints);
        terminologyPolicy = MemorySignalService.normalizeList(terminologyPolicy);
        forbiddenPatterns = MemorySignalService.normalizeList(forbiddenPatterns);
        interactionPolicy = MemorySignalService.normalizeList(interactionPolicy);
        reviewChecklist = MemorySignalService.normalizeList(reviewChecklist);
    }

    MemorySignalService.MemoryCompileHints compileHints() {
        return new MemorySignalService.MemoryCompileHints(
                styleConstraints,
                structureConstraints,
                terminologyPolicy,
                forbiddenPatterns,
                interactionPolicy,
                reviewChecklist
        );
    }
}
