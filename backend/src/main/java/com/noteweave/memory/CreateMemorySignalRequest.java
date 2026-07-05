package com.noteweave.memory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateMemorySignalRequest(
        @NotBlank @Size(max = 64) String signalType,
        @NotBlank @Size(max = 64) String sourceType,
        @Size(max = 128) String sourceId,
        @NotBlank @Size(max = 2000) String signalText,
        @NotBlank @Size(max = 64) String taskNeighborhood,
        List<String> styleConstraints,
        List<String> structureConstraints,
        List<String> terminologyPolicy,
        List<String> forbiddenPatterns,
        List<String> interactionPolicy,
        List<String> reviewChecklist
) {
}
