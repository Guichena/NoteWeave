package com.noteweave.research;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateResearchRunRequest(
        @NotBlank @Size(max = 8000) String question,
        @NotBlank @Size(max = 64) String profile,
        @Size(max = 128) String contextSnapshotId
) {
}
